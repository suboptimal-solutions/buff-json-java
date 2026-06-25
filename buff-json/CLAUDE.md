# CLAUDE.md - buff-json

## Module Purpose

The core library. Contains the public API (`BuffJson`, `BuffJsonEncoder`, `BuffJsonDecoder`) and all internal serialization logic.
No dependency on specific `.proto` definitions — works with any `com.google.protobuf.Message`.

## Package Layout

```
io.suboptimal.buffjson/
  BuffJson.java                    # Static entry point + factory: BuffJson.encoder(), BuffJson.decoder()
  BuffJsonEncoder.java             # Configurable encoder — creates JSONWriter directly, caches a single
                                   #   ProtobufMessageWriter (volatile, invalidated on setters), exposes writerModule()
  BuffJsonDecoder.java             # Configurable decoder — creates JSONReader directly, exposes readerModule()
  BuffJsonGeneratedEncoder.java    # Interface for protoc-plugin-generated encoders
  BuffJsonGeneratedDecoder.java    # Interface for protoc-plugin-generated decoders
  BuffJsonCodecHolder.java         # Interface injected into message classes via protoc insertion points
                                   #   provides buffJsonEncoder()/buffJsonDecoder() for instanceof-based discovery

io.suboptimal.buffjson.internal/
  ProtobufWriterModule.java        # fastjson2 ObjectWriterModule (intercepts Message types) — for mixed pojo+proto usage
  ProtobufReaderModule.java        # fastjson2 ObjectReaderModule (intercepts Message types) — for mixed pojo+proto usage
  ProtobufMessageWriter.java       # Three-tier dispatch in writeFields(): codec-holder → typed-accessor → reflection
  ProtobufMessageReader.java       # Stateful instance holding TypeRegistry + useGenerated. Main deserialization logic.
  GeneratedDecoderRegistry.java    # Simple ConcurrentHashMap<Descriptor, Decoder> cache for descriptor-only decode path
  MessageSchema.java               # Cached FieldInfo[] per Descriptor (reflection path); each FieldInfo carries
                                   #   both char[] nameWithColon and byte[] nameWithColonUtf8 for UTF-8 dispatch
  FieldWriter.java                 # Type-dispatched value writing (scalars, maps, repeated) for the reflection path.
                                   #   Float/double NaN/Inf branches reordered isFinite-first.
  FieldReader.java                 # Type-dispatched value reading (scalars, maps, repeated)
  WellKnownTypes.java              # Special handling for 16 well-known protobuf types.
                                   #   writeTimestampDirect()/writeDurationDirect() accept primitives directly —
                                   #   used by codegen to bypass descriptor lookup and getField() reflection.
                                   #   writeUnsignedLongString() — zero-alloc unsigned int64 formatting.

io.suboptimal.buffjson.internal.typed/
  FieldName.java                   # Record (char[] chars, byte[] utf8). writeTo(JSONWriter) dispatches on isUTF8().
  TypedFieldAccessor.java          # Sealed interface, ~20 record variants (IntAccessor, LongAccessor, ...,
                                   #   PresenceMessageAccessor, RepeatedIntAccessor, RepeatedEnumAccessor,
                                   #   MapAccessor, TypedMapAccessor, etc.). Each variant holds pre-bound
                                   #   typed lambdas (ToIntFunction<Message> etc.) and writes one field.
  TypedFieldAccessorFactory.java   # Builds accessors via LambdaMetafactory. Discovers protoc-generated
                                   #   getters (getXxx, hasXxx, getXxxValue, getXxxList, getXxxValueList,
                                   #   getXxxMap, getXxxValueMap) by name. Returns null on any failure.
  TypedMessageSchema.java          # ConcurrentHashMap<Descriptor, TypedMessageSchema> cache. FAILED sentinel
                                   #   marks descriptors where lambda binding failed (silent fallback to reflection).
                                   #   Holds a single field-number-ordered TypedFieldAccessor[] per message type
                                   #   (oneofs represented inline by OneofAccessor at their first member).
```

## Serialization Flow (hot path)

1. `BuffJsonEncoder.encode(message)`:
   - Creates `JSONWriter` directly via `JSONWriter.of()` (bypasses fastjson2 module dispatch).
   - Reuses cached `ProtobufMessageWriter(typeRegistry, useGenerated, useTyped)` (volatile field on encoder; invalidated on setters).
   - Calls `writer.writeMessage(jsonWriter, message)`.
2. `ProtobufMessageWriter.writeFields(jsonWriter, message)` — three-tier dispatch:
   - **Tier 1 — Codegen** (if `useGenerated && message instanceof BuffJsonCodecHolder`):
     - `holder.buffJsonEncoder().writeFields(jw, msg, this)` → typed getters, no reflection.
     - Nested messages call other encoders directly via `INSTANCE.writeFields(jw, msg, writer)` (no registry, no instanceof per nested).
     - Timestamp/Duration fields call `writeTimestampDirect()`/`writeDurationDirect()`.
     - Enum fields use pre-cached `String[]` name arrays.
     - Field-name writes dispatch on `boolean utf8 = jsonWriter.isUTF8()` hoisted at the top of `writeFields`.
     - Returns — never falls through.
   - **Tier 2 — Typed-accessor** (if `useTyped && !(message instanceof DynamicMessage)`):
     - `TypedMessageSchema.forMessage(descriptor, msg.getClass()).writeFields(jw, msg, this)` → LambdaMetafactory-bound typed getters.
     - First call per Descriptor: `TypedFieldAccessorFactory.create(...)` discovers `getXxx`/`hasXxx`/`getXxxList`/`getXxxValueList`/`getXxxMap`/`getXxxValueMap` by name reflection, then binds via `LambdaMetafactory.metafactory(...)` to `ToIntFunction<Message>`, `ToLongFunction<Message>`, `Predicate<Message>`, `Function<Message, Object>`, etc. Builds a single `TypedFieldAccessor[]` in field-number order, with each oneof represented by an `OneofAccessor` placed at its first-declared member (so output order matches `JsonFormat`). Cached.
     - On any failure (e.g., `DynamicMessage`, custom protoc, missing accessor), returns `null` — schema goes to `FAILED` sentinel; falls through to Tier 3.
     - **Getter-name resolution must match protoc exactly** or binding fails and the whole message silently drops to Tier 3. Two easy-to-miss cases: (1) `float` getters — pass the **direct** `(Msg)float` handle to `metafactory` and let it widen `float`→`double`; pre-adapting with `explicitCastArguments` yields a non-direct handle metafactory rejects (this had been sinking every float-containing message to reflection). (2) digit-containing field names — `toCamelCase` capitalizes after a digit (`field0name5` → `getField0Name5`), matching protobuf.
     - Enum accessors hold the dense name array **and** the `EnumDescriptor`: the array is the fast path; negative/sparse numbers fall back to `findValueByNumber` (so `NEG = -1` → name); `NullValue` enums write JSON `null`.
     - Returns once schema runs successfully.
   - **Tier 3 — Pure reflection** (fallback):
     - Iterates cached `MessageSchema.FieldInfo[]` (no `getAllFields()` TreeMap).
     - `Object value = message.getField(fd)` (boxes primitives).
     - `FieldWriter.writeValue(jw, fd, value, this)` dispatches on `JavaType`.
     - Field-name writes use `nameWithColon` (UTF-16) or `nameWithColonUtf8` (UTF-8) per the hoisted `utf8` local.
3. For MESSAGE fields in any path: `WellKnownTypes.isWellKnownType()` check first, then recurses via `writer.writeMessage()` (which re-enters the three-tier dispatch).

## Settings Flow (no ThreadLocals)

- `ProtobufMessageWriter` holds settings as instance fields: `TypeRegistry typeRegistry`, `boolean useGenerated`, `boolean useTyped`. `ProtobufMessageReader` mirrors with `typeRegistry` + `useGenerated`.
- Settings propagate through the call chain by passing the writer/reader instance (`this`) to all methods that need them.
- `FieldWriter` / `FieldReader` receive the writer/reader as a parameter for recursive nested message writes/reads.
- `WellKnownTypes.write(jw, msg, writer)` / `readWkt(reader, desc, msgReader)` receive the writer/reader for Any type support.
- Generated encoders/decoders receive the writer/reader: `writeFields(jw, msg, writer)` / `readMessage(reader, msgReader)`.
- `TypedMessageSchema.writeFields(jw, msg, writer)` receives the writer for nested message recursion (which re-enters the three-tier dispatch).

## Module Path (mixed pojo + protobuf)

For projects using `JSON.toJSONString()` with both POJOs and protobuf messages:

```java
// Register modules from configured encoder/decoder
JSONFactory.getDefaultObjectWriterProvider().register(encoder.writerModule());
JSONFactory.getDefaultObjectReaderProvider().register(decoder.readerModule());

// Now fastjson2 handles both POJOs and protobuf messages
JSON.toJSONString(myProtoMessage);  // uses the writer's settings
JSON.parseObject(json, MyMessage.class);  // uses the reader's settings
```

- `ProtobufWriterModule` holds a configured `ProtobufMessageWriter` instance
- `ProtobufReaderModule` holds a configured `ProtobufMessageReader` instance
- fastjson2 caches the ObjectWriter/ObjectReader per type after first lookup

## Proto3 JSON Spec: Key Gotchas

- **uint32/fixed32**: `Integer.toUnsignedLong()` for unsigned representation
- **uint64/fixed64**: `Long.toUnsignedString()` for unsigned quoted strings. On **decode**, both the quoted form and an *unquoted* JSON number up to `2^64-1` are accepted: `FieldReader.readUnsignedLong` parses the quoted form with `Long.parseUnsignedLong`, and the unquoted form via `readBigInteger` + `[0, 2^64)` range check (a plain `readInt64Value()` overflows past `Long.MAX_VALUE`), taking the low 64 bits.
- **int64 and all 64-bit types**: Must be quoted strings in JSON
- **Enum unknown numbers**: proto3 open enums preserve an unrecognized numeric value rather than dropping it to 0. The reflection decode path uses `EnumDescriptor.findValueByNumberCreatingIfUnknown` (codegen stores via `setXxxValue(int)`), so the number survives a re-serialization to the wire — matching `JsonFormat`.
- **NaN/Infinity**: fastjson2 writes `null` — we intercept and write quoted strings
- **-0.0**: Use `floatToRawIntBits()`/`doubleToRawLongBits()` (not `==`) for default checks
- **Enum in map values**: `message.getField()` returns `Integer` (not `EnumValueDescriptor`) for map entries
- **Wrapper types**: Serialize as unwrapped primitive values, not objects
- **FieldMask**: `snake_case` → `lowerCamelCase` conversion, comma-joined
- **Struct/Value/ListValue**: Serialize as native JSON objects/arrays/values
- **`google.protobuf.NullValue`**: Serializes as JSON `null` (not the name `"NULL_VALUE"`) wherever present — oneof/repeated/map/explicit-presence; implicit `NULL_VALUE` (0) is omitted as the default. On decode, a JSON `null` for a NullValue field means `NULL_VALUE` (marks a oneof case as set). Handled in all three paths: codegen, `FieldWriter` (reflection), and the typed enum accessors. Distinct from a `Value`-typed field, where `null` means a wrapped `NullValue`.
- **Duration nanos**: Format to 3, 6, or 9 digits (not arbitrary precision)
- **Timestamp/Duration range**: out-of-range values are not representable as RFC 3339 / duration strings, so `writeTimestampDirect`/`writeDurationDirect` validate and throw `IllegalArgumentException` (covers all three encode paths, which all funnel through these). Timestamp: seconds ∈ `[-62135596800, 253402300799]` (0001-01-01 … 9999-12-31), nanos ∈ `[0, 999999999]`. Duration: seconds ∈ `[-315576000000, 315576000000]`, nanos ∈ `[-999999999, 999999999]`, and seconds/nanos must not have opposite signs. Matches `JsonFormat`, which rejects the same inputs.
- **Any**: Requires TypeRegistry. Regular messages: `{"@type":..., ...fields}`. WKTs: `{"@type":..., "value":...}`

## Decoder Input Hardening (untrusted JSON)

The decoder consumes untrusted JSON, so a few defenses are built into the read path. All are zero-cost on the success path.

- **Strict int32/uint32 + string parsing (`FieldReader.readStrictInt32`/`readStrictUint32`/`readStrictString`)**: rather than letting fastjson2 coerce, these enforce the proto3 JSON spec so malformed input is *rejected* (a `JSONException`) instead of silently corrupting data. int32/uint32 accept an integer JSON number or a quoted integer string and reject non-integral numbers (`1.5`), out-of-range values (uint32 > 2³²−1, int32 overflow), empty/non-numeric strings, and wrong JSON types (bool/object/array); integral floats (`2.0`, `1e2`) are accepted per the spec. String fields reject any non-string token. Both decode paths use these (reflection via `readValue`; codegen via `DecoderGenerator`), and because repeated/map readers call the same helpers per element, wrong-element-type arrays are rejected too. **The common path is zero-allocation**: `isNumber()` rejects bool/object/array with no read, and the bare-number value is read via the *primitive* `readDoubleValue()` — exact for the 32-bit range (`|max| < 2⁵³`), so a fractional part (`1.5`) and out-of-range are detected via `rint`/comparison with no boxing or `BigDecimal` (measured 0 B/op, same as the old lenient `readInt64Value`). Only the non-canonical *quoted* form (`"42"`) allocates (String + `BigDecimal`), which `JsonFormat` never emits for 32-bit fields. **Caveat — don't gate on `reader.isInt()`/`readInt64Value()`**: `isInt()` means "the token starts like a number," not "is integral" (it's true for `1.5`), and `readInt64Value()` silently truncates `1.5`→`1` and coerces `true`→`1`. (int64/uint64 parsing stays as-is — those tests aren't gaps; the canonical 64-bit form is a quoted string.)
- **Top-level `null` rejected (`BuffJsonDecoder.readProto`)**: a bare top-level JSON `null` is not a valid message (proto3 JSON only allows `null` as a field value meaning "absent", or as a wrapped `NullValue`), so the top-level decode entry throws a `JSONException` instead of returning a null `Message` (which would NPE downstream). This is distinct from *empty input* — a `null`/empty Java `String` or `byte[]` is short-circuited by the public `decode(...)` methods to `null` as a lenient convenience, and from *field-level* `null` (handled in `readFieldsInto`, still means "absent"). Only the literal `null` payload reaches `readProto`. The fastjson2 module path (`readObject`) is unchanged.
- **Recursion depth cap (`WellKnownTypes.MAX_RECURSION_DEPTH = 100`)**: The `Struct`/`Value`/`ListValue` reader (`readStruct`/`readListValue`/`readJsonValueImpl`) threads an `int depth` and throws a clean `JSONException` past 100 levels instead of `StackOverflowError`. 100 matches protobuf's own limit (`CodedInputStream.DEFAULT_RECURSION_LIMIT` and `JsonFormat.Parser`'s default). Public single-arg entry points (`readStruct(reader)`, etc.) delegate to private `(reader, depth)` overloads, so generated decoders keep calling the unchanged signatures — no codegen ABI change. Note: this caps the universal Struct/Value/ListValue vector; arbitrary message nesting (self-referential message types) is not capped because that would require threading depth through the `BuffJsonGeneratedDecoder` ABI.
- **Any `@type`-first fast path** (`WellKnownTypes.readAny`): the canonical proto3 form lists `@type` first, so the descriptor is resolved before any content and the remaining fields are decoded straight off the live reader via `ProtobufMessageReader.readRemainingMessageFields` (regular messages → `DynamicMessage`) or direct WKT read — no `LinkedHashMap` buffering, no `JSON.toJSONString` + re-parse. The buffer-and-reparse slow path is retained only for the rare case where `@type` arrives after content.
- **Any empty/missing `@type` rejected** (`WellKnownTypes.readAny`): a non-empty `Any` object whose `@type` is empty (`{"@type": "", "value": ""}`) or absent (slow path) is unresolvable, so it is rejected with a `JSONException` rather than silently yielding a default `Any` (mirrors protobuf's reference parser). Only a bare `{}` is a valid typeless empty `Any` — that case is handled before any field is read and is unaffected. Conformance: `Required.Proto3.JsonInput.AnyWktRepresentationWithEmptyTypeAndValue`.
- **Timestamp/Duration range rejected at _parse_ time (`WellKnownTypes.parseTimestamp`/`parseDuration`)**: a JSON Timestamp/Duration string that parses to a valid instant but lies outside the proto3 range (e.g. `"0000-01-01T00:00:00Z"`, `"315576000001.000000000s"`) is rejected by `readTimestamp`/`readDuration` as a `JSONException` — *not* deferred to a serialize-time `IllegalArgumentException` (which the conformance runner reports as a `serialize_error` where it expects a parse rejection). Same `[-62135596800, 253402300799]` / `±315576000000` bounds the encode side enforces; here they are two `long` comparisons on an already-parsed value — no allocation, and off the hot scalar paths (only Timestamp/Duration string decode, already dominated by `Instant.parse`/`Long.parseLong`). All three decode paths share this because `DecoderGenerator` emits `WellKnownTypes.readTimestamp`/`readDuration`. Conformance: `Timestamp/DurationJsonInputToo{Small,Large}`.

## Error Contract: `JSONException` for bad input, JDK exceptions for config errors

Errors are split by *who caused them*, so a config bug never masquerades as "bad JSON":

- **User-facing — bad untrusted JSON content → `com.alibaba.fastjson2.JSONException`** (fastjson2's native type), with position context attached via `JSONReader.info(msg)` (appends offset/line/column — note fastjson2 also appends the input document to the message). Callers catch one type for any malformed payload, on **all three paths** (codegen, typed, reflection). Covers: malformed int64/uint64/float/double, timestamp, duration, base64, enum names, numeric map keys, JSON nesting depth, and a malformed/unregistered `@type` the client submitted in an `Any`.
- **Internal — server config / programmer / unreachable invariants → JDK `IllegalStateException`/`IllegalArgumentException`** (unchanged from fastjson-agnostic behavior). These are *not* driven by untrusted input, so they stay distinguishable. Covers: missing `TypeRegistry` on the encoder or decoder, encode-side `Any` type-resolution/content-parse failures (the server is serializing its own data), encode-side out-of-range `Timestamp`/`Duration` (the server's own message holds an unserializable value — `writeTimestampDirect`/`writeDurationDirect` throw `IllegalArgumentException`), a bad target `Class` passed to `decode`, and the unreachable "Unknown well-known type" / "Unsupported map key type" guard arms.

Implementation:

- **Where conversion happens** — value parsing lives in `FieldReader` helpers (`readSignedLong`, `readUnsignedLong`, `readFloatValue`, `readDoubleValue`, `readBytes`, `enumNumber`, `parseIntKey`/`parseUnsignedIntKey`/`parseLongKey`/`parseUnsignedLongKey`) and `WellKnownTypes` (`readTimestamp`, `readDuration`, `readAny`/`resolveAnyType`). Each wraps the JDK exception (`NumberFormatException`, `DateTimeParseException`, base64/enum `IllegalArgumentException`) and rethrows `JSONException`, preserving the original as the cause.
- **Codegen routes through the same helpers** — `DecoderGenerator` emits calls to `FieldReader.readBytes`/`enumNumber`/`parse*Key` (not inline `BASE64.decode`/`Enum.valueOf`/`Long.parseLong`), so generated decoders get the identical contract without duplicating try/catch. These helpers are `public` precisely because generated code lives in the user's package.
- **`try/catch` is free on the success path** (HotSpot exception tables), so this is zero-cost normalization.
- **Internal helpers stay JDK-typed** — `parseTimestamp`/`parseDuration` throw `DateTimeParseException`/`IllegalArgumentException` (malformed format *or* out-of-range) but are always wrapped by their `read*` callers, so the type never escapes: `readTimestamp` catches `DateTimeParseException | IllegalArgumentException`, `readDuration` catches `IllegalArgumentException` — both cover the parse-time range check.
- **`Any` registry split** — in `resolveAnyType`, a `null` registry → `IllegalStateException` (decoder was never configured); a well-formed-but-unregistered or malformed `@type` → `JSONException` + offset (the client sent it).

## Dependencies

- `com.google.protobuf:protobuf-java` — Message, Descriptor, TypeRegistry, DynamicMessage
- `com.alibaba.fastjson2:fastjson2` — JSONWriter, JSONReader, ObjectWriterModule, ObjectReaderModule

