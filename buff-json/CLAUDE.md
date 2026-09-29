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
  BuffJsonDecoder.java             # Configurable decoder — creates JSONReader directly, exposes readerModule().
                                   #   With generated decoders + setFastPath(true) (default) tries the canonical-input
                                   #   fast path first (decodeFast), then the general decoder.
  BuffJsonGeneratedEncoder.java    # Interface for protoc-plugin-generated encoders
  BuffJsonGeneratedDecoder.java    # Interface for protoc-plugin-generated decoders
  BuffJsonCodecHolder.java         # Interface injected into message classes via protoc insertion points
                                   #   provides buffJsonEncoder()/buffJsonDecoder() for instanceof-based discovery

io.suboptimal.buffjson.internal/
  ProtobufWriterModule.java        # fastjson2 ObjectWriterModule (intercepts Message types) — for mixed pojo+proto usage
  ProtobufReaderModule.java        # fastjson2 ObjectReaderModule (intercepts Message types) — for mixed pojo+proto usage
  ProtobufMessageWriter.java       # Three-tier dispatch in writeFields(): codec-holder → typed-accessor → reflection
  ProtobufMessageReader.java       # Stateful instance holding TypeRegistry + useGenerated. Main deserialization logic.
  TypedMessageReaderSchema.java   # ClassValue-cached MethodHandle builder setters; per-field descriptor fallback;
                                   #   NameTable resolves member names by raw 4-byte prefix + FieldNameMatcher first
  FieldNameMatcher.java            # Single implementation of fastjson2's nextIfName4MatchN argument layout (used by the
                                   #   protoc plugin to emit calls and by TypedMessageReaderSchema to make them)
  FastInput.java                   # Cursor + public-static token helpers (ws, plainStringEnd, int32At, ...) behind the
                                   #   generated straight-line readFast(); reads only *canonical* proto3 JSON and throws
                                   #   a stackless Bail for anything else (the general decoder then re-reads everything)
  FastDouble.java                  # Eisel-Lemire: correctly rounded decimal -> double for up to 19 digits (128-bit
                                   #   powers-of-five table kept as text), so 17-digit doubles need no Double.parseDouble
  ProtobufJavaNames.java           # Shared protoc accessor-name conversion
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

- **Cached names and descriptors**: `FieldName.of` JSON-escapes custom names once for UTF-16 and UTF-8. Map key/value descriptors and typed message WKT checks are cached in schemas.
- **Deprecated fields**: retain normal presence and encoding behavior on all three paths.
- **Numeric map keys**: written directly as quoted primitives, with unsigned conversion for uint32/fixed32 and uint64/fixed64. Boolean keys use constant strings. Long keys fall back to String formatting under BrowserCompatible or WriteClassName to prevent fastjson2 from changing their spelling.
- **Compiled WKTs**: Timestamp, Duration, Struct, Value, and ListValue use concrete getters; DynamicMessage retains descriptor-based fallback and the same range checks.

- **uint32/fixed32**: `Integer.toUnsignedLong()` for unsigned representation
- **uint64/fixed64**: `WellKnownTypes.writeUnsignedLongString()` writes quoted unsigned values without an intermediate String; large values use one exact-size 19/20-byte buffer. On **decode**, both the quoted form and an *unquoted* JSON number up to `2^64-1` are accepted: `FieldReader.readUnsignedLong` parses the quoted form with `Long.parseUnsignedLong`, and the unquoted form via `readBigInteger` + `[0, 2^64)` range check (a plain `readInt64Value()` overflows past `Long.MAX_VALUE`), taking the low 64 bits.
- **int64 and all 64-bit types**: Must be quoted strings in JSON
- **Enum unknown numbers**: proto3 open enums preserve an unrecognized numeric value rather than dropping it to 0. The reflection decode path uses `EnumDescriptor.findValueByNumberCreatingIfUnknown` (codegen stores via `setXxxValue(int)`), so the number survives a re-serialization to the wire — matching `JsonFormat`.
- **NaN/Infinity**: fastjson2 writes `null` — we intercept and write quoted strings
- **-0.0**: Use `floatToRawIntBits()`/`doubleToRawLongBits()` (not `==`) for default checks
- **Enum in map values**: `message.getField()` returns `Integer` (not `EnumValueDescriptor`) for map entries
- **Wrapper types**: Serialize as unwrapped primitive values, not objects
- **FieldMask**: `snake_case` → `lowerCamelCase` conversion, comma-joined
- **Struct/Value/ListValue**: Serialize as native JSON objects/arrays/values
- **`google.protobuf.NullValue`**: Serializes as JSON `null` (not the name `"NULL_VALUE"`) wherever present — oneof/repeated/map/explicit-presence; implicit `NULL_VALUE` (0) is omitted as the default. On decode, a JSON `null` for a NullValue field means `NULL_VALUE` (marks a oneof case as set). Handled in all three paths: codegen, `FieldWriter` (reflection), and the typed enum accessors. Distinct from a `Value`-typed field, where `null` means a wrapped `NullValue`.
- **`null` as a list element or map value of `Value` / `NullValue`**: JSON `null` is a *value* there (`Value{null_value}` / `NULL_VALUE`), exactly as for a singular field — `[null]` in a `repeated google.protobuf.Value` and `{"k":null}` in a `map<string, Value>` / `map<string, NullValue>` each yield one element/entry, matching `JsonFormat`. All three decode paths agree (`FieldReader.nullElement`/`NULL_VALUE_MESSAGE` for the reflection and typed-builder readers, the generated map readers for codegen). Before, the runtime paths dropped such list elements and the codegen path dropped such map entries (a runtime `Value` map value became a `Value` with no kind set). For every *other* element type the paths still differ, as before: a `null` list element is skipped by the runtime readers and rejected by generated ones; a `null` map value becomes the value type's default in the runtime readers and is skipped (no entry) in generated ones. `JsonFormat` rejects all of those; we have not tightened them. Coverage: `BuffJsonNullElementsTest`.
- **Duration nanos**: Format to 3, 6, or 9 digits (not arbitrary precision)
- **Timestamp/Duration range**: out-of-range values are not representable as RFC 3339 / duration strings, so `writeTimestampDirect`/`writeDurationDirect` validate and throw `IllegalArgumentException` (covers all three encode paths, which all funnel through these). Timestamp: seconds ∈ `[-62135596800, 253402300799]` (0001-01-01 … 9999-12-31), nanos ∈ `[0, 999999999]`. Duration: seconds ∈ `[-315576000000, 315576000000]`, nanos ∈ `[-999999999, 999999999]`, and seconds/nanos must not have opposite signs. Matches `JsonFormat`, which rejects the same inputs.
- **Any**: Requires TypeRegistry. Regular messages: `{"@type":..., ...fields}`. WKTs: `{"@type":..., "value":...}`

## Runtime Decoding

`BuffJsonDecoder` defaults to generated decoders, then `TypedMessageReaderSchema`, then descriptor parsing. `setGeneratedDecoders(false)` forces runtime decoding; also setting `setTypedAccessors(false)` exercises descriptor field access. Both flags are passed through nested reads and invalidate the cached reader when changed.

The typed schema is cached per concrete class with `ClassValue`. It binds public builder `setXxx`/`setXxxValue`, `addXxx`/`addXxxValue`, and `putXxx`/`putXxxValue` methods once using MethodHandles. Scalar primitive arguments stay primitive through `invokeExact`; maps use direct insertion without temporary protobuf map entries. Ordinary javac lambdas capture the handles; there is no dynamic LambdaMetafactory call or manual bytecode generation. Native images still require application reachability metadata for the accessed methods. Unsupported accessors fall back per field, and `DynamicMessage` retains descriptor parsing.

Nested concrete messages use their own builders rather than parsing into DynamicMessage and copying. Canonical timestamps (UTC, 0/3/6/9 fractional digits) use validated digit parsing; offsets, other precisions and unusual forms retain the original parser. Both improvements also apply to the descriptor fallback where a concrete parent is available; timestamp parsing is shared with generated decoders.

## Canonical-Input Fast Path (generated `readFast` + `FastInput`)

`BuffJsonDecoder.decode(byte[]…)` / `decode(String…)` first hand the document to the message's generated `readFast(FastInput)` (a default method on `BuffJsonGeneratedDecoder` that always bails, so older generated code keeps working). It follows one rule: **accept only input whose meaning is unambiguous; anything else throws the stackless `FastInput.Bail`** (or runs off the end of the array, an `IndexOutOfBoundsException` treated the same way). `decodeFast` then returns `null` and the *whole document* is decoded again by the general decoder, so results, exception types and messages for non-canonical or invalid input are exactly the general decoder's. The fast path never reports a parse error itself and never decides what a questionable input *means*.

**Shape of the code.** `FastDecoderGenerator` (protoc plugin) emits a *straight-line* reader per message: the position is a local `int p`, the document a local `byte[] b`, tokens are recognised by inline comparisons plus tiny public-static helpers of `FastInput` that take a position and return the next one (`ws`, `plainStringEnd`, `int32At`/`uint32At`/`…KeyAt` returning `value << 32 | position`, `i4`/`l8`); one small static method per field (`f0`, `f1`, …) keeps every generated method far below the JIT's 8000-byte huge-method limit. The cursor object is only used, with `pos()`/`pos(int)` handing the position over, for the rarer values (64-bit integers, float/double, bytes, Timestamp/Duration, escaped or non-ASCII strings, `skipValue` of unknown members). Keeping the position in a register instead of a field that every token method reloads and stores is what makes this ~1.5× faster than driving a cursor object token by token (measured: 2.8M vs 4.1M ops/s on a string/int recursive message; the generic-cursor version was *slower* than the general decoder on shapes without doubles). Member names are matched by `switch` on the first four bytes plus `FieldNameMatcher.inlineTest` (8/4/1-byte compares), for both the JSON name and the proto name (`snake_case` producers stay on the fast route); wide messages (≥ 64 members) split the matcher into 8 methods by a hash of the prefix; messages with more than 800 members get no fast reader at all.

- **Accepted**: insignificant whitespace anywhere; integers `-?(0|[1-9][0-9]*)` (64-bit optionally quoted; unsigned in range); float/double as a number or quoted `NaN`/`Infinity`/`-Infinity`, **correctly rounded** (Clinger's exact path for short numbers, `FastDouble` — Eisel-Lemire — for up to 19 digits, JDK parser beyond; floats via the double unless it lies exactly halfway between two floats); strings with standard escapes and well-formed UTF-8 (escape-free ones are validated and then decoded by the JDK); base64 `bytes`; enum names (matched by length and bytes, no `String`) and numbers; canonical UTC RFC 3339 `Timestamp`, `Duration`; wrappers, `Empty`; repeated, maps, oneofs, nested messages; `null` (skipped, or `NULL_VALUE` for a singular `Value`/`NullValue`).
- **Bails**: escapes or non-ASCII in member names, leading zeros / `+` / fractions / exponents in integers, unknown enum names, malformed UTF-8 or raw control characters in strings, malformed base64, non-canonical timestamps/durations, more than 19 digits in a 64-bit integer, nesting deeper than `FastInput.MAX_DEPTH` (100 objects, keeps recursion bounded whatever a client sends; deeper documents get the general decoder's behaviour, which is unchanged), trailing garbage, and — when the member is *present* — `Any`, `Struct`, `Value`, `ListValue`, `FieldMask` (no fast reader: a document holding one pays for the scan up to that member, measured at ±3% on shapes that always bail).
- **Inputs**: `byte[]` (slices are read in place — the generated code may look past a slice's end, and `FastInput.finished()` rejects a document that did) and Latin-1 coded `String`s (`JDKUtils.STRING_CODER`/`STRING_VALUE` expose the backing array without a copy; the bytes are *characters*, never UTF-8: `C3 A9` is the two characters `Ã©` in a Latin-1 String but the single character `é` in a `byte[]` — hence the cursor's `latin1` flag). UTF-16 coded Strings (any char > 0xFF) and JVMs where fastjson2 cannot expose the array skip the fast path.
- **Switch**: `setFastPath(false)` forces the general decoder; it is ignored without generated decoders. Tests run decode assertions on both.
- **Why it can be trusted**: `BuffJsonFastPathTest` compares fast vs general on canonical `JsonFormat`/BuffJson output (compact, pretty, proto names) of random messages of 16 message types — all must be *accepted* and exact — on thousands of mutated / respelled / truncated documents (same message or same exception type; and whenever `readFast` accepts, the general decoder accepts with an equal result), on every single-byte damage to every member name, on Latin-1 and UTF-16 Strings, slices and nesting depth. `FastInputTest` pins every helper (integer readers against exact `BigInteger` arithmetic on 300k random tokens, string decoding against the JDK's strict UTF-8 decoder on random and damaged input); `FastDoubleTest` re-derives the Eisel-Lemire table with `BigInteger` and compares millions of conversions with `Double.parseDouble` (halfway cases, subnormals, overflow). Every injected defect in the matcher, string scan, integer, double and generator code is caught (mutation-checked).
- **Performance contract** (JDK 25, see `docs/decode-performance-java25.md`): a large win where documents are canonical and consist of supported kinds; documents that bail pay the aborted scan plus the general decode.

## Decoder Input Hardening (untrusted JSON)

The decoder consumes untrusted JSON, so a few defenses are built into the read path. All are zero-cost on the success path.

- **Strict int32/uint32 + string parsing (`FieldReader.readStrictInt32`/`readStrictUint32`/`readStrictString`)**: rather than letting fastjson2 coerce, these enforce the proto3 JSON spec so malformed input is *rejected* (a `JSONException`) instead of silently corrupting data. int32/uint32 accept an integer JSON number or a quoted integer string and reject non-integral numbers (`1.5`), out-of-range values (uint32 > 2³²−1, int32 overflow), empty/non-numeric strings, and wrong JSON types (bool/object/array); integral floats (`2.0`, `1e2`) are accepted per the spec. String fields reject any non-string token. All three decode paths use these (reflection and typed setters via `FieldReader`; codegen via `DecoderGenerator`), and because repeated/map readers call the same helpers per element, wrong-element-type arrays are rejected too. **The common path is zero-allocation**: `isNumber()` rejects bool/object/array with no read, and the bare-number value is read via the *primitive* `readDoubleValue()` — exact for the 32-bit range (`|max| < 2⁵³`), so a fractional part (`1.5`) and out-of-range are detected via `rint`/comparison with no boxing or `BigDecimal` (measured 0 B/op, same as the old lenient `readInt64Value`). Only the non-canonical *quoted* form (`"42"`) allocates (String + `BigDecimal`), which `JsonFormat` never emits for 32-bit fields. **Caveat — don't gate on `reader.isInt()`/`readInt64Value()`**: `isInt()` means "the token starts like a number," not "is integral" (it's true for `1.5`), and `readInt64Value()` silently truncates `1.5`→`1` and coerces `true`→`1`. (int64/uint64 parsing stays as-is — those tests aren't gaps; the canonical 64-bit form is a quoted string.)
- **Top-level `null` rejected (`BuffJsonDecoder.readProto`)**: a bare top-level JSON `null` is not a valid message (proto3 JSON only allows `null` as a field value meaning "absent", or as a wrapped `NullValue`), so the top-level decode entry throws a `JSONException` instead of returning a null `Message` (which would NPE downstream). This is distinct from *empty input* — a `null`/empty Java `String` or `byte[]` is short-circuited by the public `decode(...)` methods to `null` as a lenient convenience, and from *field-level* `null` (handled in `readFieldsInto`, still means "absent"). Only the literal `null` payload reaches `readProto`. The fastjson2 module path (`readObject`) is unchanged.
- **A message value must be a JSON object (`FieldReader.objectStart`)**: every message reader (generated decoders, `ProtobufMessageReader` runtime/descriptor paths, `Struct`) consumes `{` or throws a `JSONException`. Before, the `{` was optional, so a repeated message field with a non-object element (`{"items":[1]}`, `[x]`, `[,]`, `[true]`, a truncated `[`) made the element reader return an empty message *without consuming any input*, and the array loop appended empty messages until `OutOfMemoryError` — an endless loop reachable with ~12 bytes of untrusted input, on all three decode paths. Top-level empty/whitespace-only input still decodes to the default instance (handled in `BuffJsonDecoder.readProto`). A `null` *element* of a repeated message field is skipped by the runtime readers and rejected by generated ones (pre-existing difference; only the endless loop was fixed). Regression coverage: `BuffJsonHardeningTest.NonObjectMessageValues`.
- **Recursion depth cap (`WellKnownTypes.MAX_RECURSION_DEPTH = 100`)**: The `Struct`/`Value`/`ListValue` reader (`readStruct`/`readListValue`/`readJsonValueImpl`) threads an `int depth` and throws a clean `JSONException` past 100 levels instead of `StackOverflowError`. 100 matches protobuf's own limit (`CodedInputStream.DEFAULT_RECURSION_LIMIT` and `JsonFormat.Parser`'s default). Public single-arg entry points (`readStruct(reader)`, etc.) delegate to private `(reader, depth)` overloads, so generated decoders keep calling the unchanged signatures — no codegen ABI change. Note: this caps the universal Struct/Value/ListValue vector; arbitrary message nesting (self-referential message types) is not capped because that would require threading depth through the `BuffJsonGeneratedDecoder` ABI.
- **Any `@type`-first fast path** (`WellKnownTypes.readAny`): the canonical proto3 form lists `@type` first, so the descriptor is resolved before any content and the remaining fields are decoded straight off the live reader via `ProtobufMessageReader.readRemainingMessageFields` (regular messages → `DynamicMessage`) or direct WKT read — no `LinkedHashMap` buffering, no `JSON.toJSONString` + re-parse. The buffer-and-reparse slow path is retained only for the rare case where `@type` arrives after content.
- **Any empty/missing `@type` rejected** (`WellKnownTypes.readAny`): a non-empty `Any` object whose `@type` is empty (`{"@type": "", "value": ""}`) or absent (slow path) is unresolvable, so it is rejected with a `JSONException` rather than silently yielding a default `Any` (mirrors protobuf's reference parser). Only a bare `{}` is a valid typeless empty `Any` — that case is handled before any field is read and is unaffected. Conformance: `Required.Proto3.JsonInput.AnyWktRepresentationWithEmptyTypeAndValue`.
- **Timestamp/Duration range rejected at _parse_ time (`WellKnownTypes.parseTimestamp`/`parseDuration`)**: a JSON Timestamp/Duration string that parses to a valid instant but lies outside the proto3 range (e.g. `"0000-01-01T00:00:00Z"`, `"315576000001.000000000s"`) is rejected by `readTimestamp`/`readDuration` as a `JSONException` — *not* deferred to a serialize-time `IllegalArgumentException` (which the conformance runner reports as a `serialize_error` where it expects a parse rejection). Same `[-62135596800, 253402300799]` / `±315576000000` bounds the encode side enforces; here they are two `long` comparisons on an already-parsed value — no allocation, and off the hot scalar paths (only Timestamp/Duration string decode, canonical timestamps use a validated digit parser and `LocalDate.toEpochDay`; other timestamp forms retain `Instant.parse`). All three decode paths share this because `DecoderGenerator` emits `WellKnownTypes.readTimestamp`/`readDuration`. Conformance: `Timestamp/DurationJsonInputToo{Small,Large}`.

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

