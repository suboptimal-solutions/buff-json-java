# CLAUDE.md - buff-json (root)

## Project Overview

Fast protobuf-to-JSON serializer for Java using fastjson2 as the JSON writing engine.
Up to ~10x faster than `JsonFormat.printer()` with the optional protoc plugin (~4-5x without).
Proto3 JSON spec compliant, including all 16 well-known types.
Includes JSON Schema generation from protobuf descriptors (separate module, no fastjson2 dependency).
Includes Swagger/OpenAPI `ModelConverter` for exposing protobuf message schemas in OpenAPI 3.1 docs.

## Architecture

Three encoding paths in `ProtobufMessageWriter.writeFields()`, tried in order:

```
BuffJsonEncoder.encode(message)
  -> creates JSONWriter directly (bypasses fastjson2 module dispatch)
  -> ProtobufMessageWriter(typeRegistry, useGenerated, useTyped).writeMessage(jsonWriter, message)
    -> writeFields(jsonWriter, message)
      -> 1. if useGenerated && message instanceof BuffJsonCodecHolder    # CODEGEN
            holder.buffJsonEncoder().writeFields(jw, msg, writer)        # typed getters, no reflection
              -> nested: OtherEncoder.INSTANCE.writeFields(jw, nested, writer)   # direct call
              -> WKT Timestamp/Duration: writeTimestampDirect(seconds, nanos)    # no reflection
              -> WKT wrappers: jsonWriter.writeInt32(wrap.getValue())             # no reflection
              -> field name: writeName<n>Raw(NAME_X_W0[, ...])                    # packed words, no branch
      -> 2. if useTyped && !(message instanceof DynamicMessage)          # TYPED-ACCESSOR
            TypedMessageSchema.forMessage(...).writeFields(jw, msg, this) # LambdaMetafactory-bound getters
              -> ToIntFunction<Message>, ToLongFunction<Message>, Predicate<Message>, Function<Message,?>
              -> RepeatedInt/Long/Double/Float/Bool/String/MessageAccessor       # primitive lists, no boxing
              -> TypedMapAccessor uses getXxxMap() / getXxxValueMap()
      -> 3. MessageSchema.forDescriptor(...) iteration                   # PURE REFLECTION
            Object value = message.getField(fd); FieldWriter.writeValue(...)  # boxing
              -> field name: if (utf8) writeNameRaw(nameWithColonUtf8); else writeNameRaw(nameWithColon);
```

**1. Codegen path** (~10x JsonFormat) — protoc plugin generates `*JsonEncoder` per message. Each encoder calls typed getters directly (`msg.getId()` → `int`), eliminating reflection, boxing, runtime type dispatch, and ConcurrentHashMap lookups.

Codegen optimizations:
- **Direct nested encoder calls** — `AddressJsonEncoder.INSTANCE.writeFields(jw, msg, writer)` instead of routing through `ProtobufMessageWriter` (avoids ConcurrentHashMap lookup + instanceof check per nested message).
- **Inline WKT Timestamp/Duration** — `WellKnownTypes.writeTimestampDirect(jsonWriter, ts.getSeconds(), ts.getNanos())` bypasses descriptor string switch, field cache lookup, and `getField()` reflection+boxing.
- **Inline WKT wrappers** — an `Int32Value`/`StringValue`/… field emits `jsonWriter.writeInt32(wrap.getValue())` etc. directly, so the nine wrapper types skip `WellKnownTypes.write()`'s full-name String switch, descriptor cache lookup and `getField()` boxing entirely.
- **Pre-cached enum name arrays** — static `String[]` built at class init from enum descriptor values, replaces `forNumber()` + `getValueDescriptor().getName()` per write.
- **String map key optimization** — avoids redundant `toString()` for String-typed map keys.
- **Zero-alloc int64**: `writeString((long) v)` (signed) and `WellKnownTypes.writeUnsignedLongString(jw, v)` (unsigned) — no `Long.toString()` / `Long.toUnsignedString()` String allocation.
- **Zero-alloc bytes**: `jsonWriter.writeBase64(v.toByteArray())` — no Base64 String intermediate.
- **`isFinite()`-first** float/double NaN/Inf branches.
- **Word-packed field names** — a name of 2–16 printable-ASCII characters (i.e. essentially every protobuf field) is packed into one or two `long`s at class init and written with fastjson2's `writeName2Raw(long)` … `writeName16Raw(long, long)`: one or two `Unsafe.putLong` stores instead of a static array load plus `arraycopy`. `JSONWriterUTF16` widens the *same* packed bytes on the way out, so there is **no `isUTF8()` branch** on this path — the `char[]`/`byte[]` pair and the hoisted `boolean utf8` local are emitted only for the rare unpackable `json_name`. See `FieldNames`.
- **No boxing in repeated numeric fields** — repeated int32/int64/double/float/bool lists are `instanceof`-narrowed to `Internal.IntList`/`LongList`/`DoubleList`/`FloatList`/`BooleanList` and read with `getInt(i)`/`getLong(i)`/…, avoiding the `Integer.valueOf`/`Long.valueOf` allocation `List.get(i)` performs per element.
- **Bulk repeated strings** — `jsonWriter.writeString(values)` hands the whole `List<String>` to fastjson2, which writes brackets, commas and per-element escaping with one capacity check.

**2. Typed-accessor path** (~6x JsonFormat) — used when codegen is disabled or unavailable. `TypedMessageSchema` caches per-Descriptor arrays of `TypedFieldAccessor` records (sealed interface, ~20 variants) whose getter slots are bound via `LambdaMetafactory` to the protoc-generated typed getters. No `getField()` reflection, no boxing. Specialized `RepeatedInt/Long/String/MessageAccessor` and `TypedMapAccessor` eliminate per-element switch dispatch. A `FAILED` sentinel marks descriptors where lambda binding failed (silent fallback to path 3).

**3. Pure-reflection path** — used for `DynamicMessage` (no compiled getters → can't bind lambdas) or descriptors where `LambdaMetafactory` failed. Iterates cached `MessageSchema.FieldInfo[]`, calls `message.getField(fd)` (boxes primitives) — after `hasField` for fields with presence, so an absent optional field costs no boxed read — and `FieldWriter` switches on `JavaType`. Field names go through the same `FieldName` (`char[]`/`byte[]`, dispatched on `isUTF8()`) as the typed path.

fastjson2 handles: buffer pooling, number formatting, string escaping, UTF-8 encoding, Base64 encoding (`writeBase64(byte[])`).
We handle: protobuf field extraction, proto3 JSON spec compliance, well-known types, epoch→calendar arithmetic for timestamps.

## Public API

```java
// Simple usage (no Any fields)
BuffJsonEncoder encoder = BuffJson.encoder();
String json = encoder.encode(message);

// With TypeRegistry (for Any fields)
BuffJsonEncoder encoder = BuffJson.encoder()
    .setTypeRegistry(TypeRegistry.newBuilder()
        .add(MyMessage.getDescriptor())
        .build());
String json = encoder.encode(message);

// Force typed-accessor path (skip generated encoders, for benchmarking/testing)
BuffJsonEncoder typedEncoder = BuffJson.encoder().setGeneratedEncoders(false);

// Force pure reflection path (for benchmarking and 3-path test coverage)
BuffJsonEncoder reflectionEncoder = BuffJson.encoder()
    .setGeneratedEncoders(false)
    .setTypedAccessors(false);

// Mixed pojo + protobuf: register fastjson2 module from encoder/decoder
JSONFactory.getDefaultObjectWriterProvider().register(encoder.writerModule());
JSONFactory.getDefaultObjectReaderProvider().register(decoder.readerModule());
```

- `BuffJson` — static entry point + factory for `BuffJsonEncoder` and `BuffJsonDecoder`
- `BuffJsonEncoder` — configurable encoder. Holds optional `TypeRegistry`, `useGeneratedEncoders`, `useTypedAccessors` flags, and a volatile cached `ProtobufMessageWriter` (invalidated on any setter). Creates `JSONWriter` directly (no fastjson2 module dispatch). Exposes `writerModule()` for fastjson2 registration.
- `BuffJsonDecoder` — configurable decoder. Creates `JSONReader` directly. Exposes `readerModule()` for fastjson2 registration.
- `BuffJsonGeneratedEncoder<T>` — interface implemented by protoc-plugin-generated encoders.
- `BuffJsonGeneratedDecoder<T>` — interface implemented by protoc-plugin-generated decoders.
- `BuffJsonCodecHolder` — interface injected into protobuf message classes via protoc insertion points. Provides `buffJsonEncoder()` and `buffJsonDecoder()` for codec discovery via `instanceof` — no ServiceLoader or reflection.

## Key Design Decisions

- **Direct JSONWriter/JSONReader** — encoder/decoder create fastjson2 writers/readers directly, bypassing the module dispatch and provider lookup. This eliminates per-call overhead from fastjson2's `JSON.toJSONString()`/`JSON.parseObject()`.
- **Instance-based settings** — `ProtobufMessageWriter` and `ProtobufMessageReader` hold `TypeRegistry`, `useGenerated`, `useTyped` as instance fields. Settings flow through the call chain via `this` — no ThreadLocals.
- **Cached writer in `BuffJsonEncoder`** — `volatile ProtobufMessageWriter cachedWriter`, lazily constructed and invalidated on `setTypeRegistry`/`setGeneratedEncoders`/`setTypedAccessors`. Eliminates per-encode allocation. Volatile + idempotent construction is enough — racy double-allocate is harmless because `ProtobufMessageWriter` is immutable once constructed.
- **Module exposure** — `encoder.writerModule()` and `decoder.readerModule()` return fastjson2 modules backed by configured writer/reader instances, for mixed pojo+protobuf projects using `JSON.toJSONString()`.
- **`MessageSchema` caching**: One-time cost per Descriptor. Avoids `getAllFields()` TreeMap allocation.
- **`TypedMessageSchema` caching**: Per-Descriptor `LambdaMetafactory`-bound typed accessors, lazily built. `FAILED` sentinel marks descriptors where binding failed (silent fallback to reflection path).
- **Word-packed field names (`FieldNames`)**: fastjson2's `writeName<n>Raw(long[, long])` family takes the name already packed into machine words and stores it with one or two `Unsafe.putLong`s — it is what fastjson2's own ASM/`@JSONCompiled` writers use, and it is the main remaining gap to the POJO ceiling. `FieldNames` derives the constants by reading native-order words straight out of the literal `"name":` text (offset 0, except lengths 8 and 16 where fastjson2 emits the opening quote itself). **The same constants serve UTF-8 and UTF-16 writers**, because `JSONWriterUTF16` widens the packed ASCII to `char`s on the way out — so packed names need no `isUTF8()` branch at all. **Codegen only, by measurement**: the plugin knows each name's length so it emits the exact `writeName<n>Raw` call with no dispatch (+12–13% on `SimpleMessage`). Routing the runtime paths through it too requires a `switch` on the length — an indirect branch in the hottest code keyed on a per-field value — which cost 10% on `ComplexMessage.buffJsonRuntime`, so `FieldName` keeps the pre-encoded `char[]`/`byte[]` pair. `FieldNames` still supplies those arrays, and codegen falls back to them for the rare unpackable `json_name`. A `json_name` that is not `FieldNames.isRawWritable` at all (non-ASCII, or carrying a quote/backslash/control char) goes through `writeName(String)` + `writeColon()` so fastjson2 escapes and transcodes it — the old `(byte) charAt(i)` array builder corrupted such names, and the generator emitted them into Java literals unescaped, which did not compile.
- **Shared `JSONWriter.Context`**: `JSONWriter.of()` allocates a fresh `Context` per call to carry configuration that never changes between encodes. `BuffJsonEncoder` holds one `JSONFactory.createWriteContext()` and passes it to `JSONWriter.of(ctx)`/`ofUTF8(ctx)`. The context is read-only during writing, so one per encoder is safe to share across threads.
- **No boxing on repeated primitives**: protobuf-java backs repeated numeric fields with `Internal.IntList`/`LongList`/`DoubleList`/`FloatList`/`BooleanList`; `List.get(i)` on those boxes every element. Both codegen and the typed accessors `instanceof`-narrow and read via the primitive getters, keeping a generic `List` branch for non-protobuf list implementations.
- **`message.getField(descriptor)`** for field access in pure-reflection path only (involves boxing for primitives). Codegen and typed-accessor paths bypass it entirely.
- **`Float.floatToRawIntBits() == 0`** for default value checks (correctly handles `-0.0`).
- **Native fastjson2 methods** for zero-allocation writes: `writeString(long)` for signed int64 (no `Long.toString()` allocation), `writeBase64(byte[])` for bytes fields (no intermediate Base64 String), `writeNameRaw(byte[])` for field names on UTF-8 path (direct `arraycopy`).
- **`WellKnownTypes.writeUnsignedLongString()`** for uint64/fixed64: delegates to `writeString(long)` when value fits in signed range, formats to `byte[]` + `writeStringLatin1()` for large unsigned values.
- **`Integer.toUnsignedLong()`** for uint32.
- **Zero-allocation timestamps**: `writeTimestampDirect()` uses Howard Hinnant's civil_from_days algorithm to convert epoch seconds to year/month/day/hour/minute/second using pure integer arithmetic — no `Instant` or `OffsetDateTime` allocation. Exact-size byte buffers (20/24/27/30 bytes) eliminate `Arrays.copyOf()`.
- **Exact-size duration buffers**: `writeDurationDirect()` computes buffer size from `longDigitCount(seconds)` + `nanosDigitCount(nanos)` to avoid over-allocation and `Arrays.copyOf()`.
- **Builder pattern** (`BuffJsonEncoder`) mirrors `JsonFormat.printer()` style, extensible for future options.
- **Insertion point discovery**: Protoc plugin uses `message_implements` and `class_scope` insertion points to inject `BuffJsonCodecHolder` into generated message classes. At runtime, `instanceof BuffJsonCodecHolder` replaces ServiceLoader — zero overhead, JIT-friendly, no reflection.
- **`DynamicMessage` guard**: `DynamicMessage` never implements `BuffJsonCodecHolder`, so the `instanceof` check naturally excludes it.
- **Decoder descriptor cache**: `GeneratedDecoderRegistry` is a simple `ConcurrentHashMap<Descriptor, Decoder>` populated as a side-effect of `instanceof` lookups. Used only for the descriptor-only decode path (nested messages in the runtime reflection path).
- **Baked JSON Schema resource (single home for comments, no injected code)**: The protoc plugin bakes one full JSON Schema per message to `META-INF/buff-json/schema/<fullName>.json` by reusing `ProtobufSchema` at code-gen time (comments from `SourceCodeInfo`, which protoc always sends to plugins; buf.validate constraints from a registered `ExtensionRegistry`). Nothing is injected into protobuf's generated classes — they keep depending only on `protobuf-java`. There is **no** separate comments resource: comments exist only inside the baked schema. At runtime `ProtobufSchema.generateJson()` returns the baked text verbatim, and `ProtobufSchema.generate()→Map` does the live descriptor walk (for exact `Integer`/`Long`/`Float`/`Double` types a JSON round-trip can't preserve) then **overlays the `description` fields** from the baked schema (parsed with fastjson2, structurally matched). When no baked schema exists (e.g. a `DynamicMessage` from a `.desc` with `--include_source_info`), comments come from `SourceCodeInfo`. GraalVM native-image friendly: `buff-json-schema` ships one `resource-config.json` whose glob includes the schema resources from any jar on the classpath, so consumers need no native-image config of their own.

## Encoding Performance

`docs/encoding-performance.md` is the standing audit of the encode path: what was measured and
applied, what measured as *nothing* and why (HotSpot already handled it), and the ranked backlog
of remaining opportunities with the reasoning behind each. Read it before optimizing the write
path — several attractive-looking ideas are recorded there as already-tried or already-refuted.

## Allocation Regression Check

`./allocation-check.sh` runs JMH `-prof gc` on a representative subset of benchmarks (SimpleMessage codegen+runtime × UTF-16+UTF-8, ComplexMessage codegen+runtime, DoubleHeavy codegen × UTF-16+UTF-8) and asserts `gc.alloc.rate.norm` (bytes per `@Benchmark` invocation) stays within per-benchmark budgets defined in the script. Total runtime ~1 minute. `--quick` flag for local iteration. Wired into CI as a separate `allocation-check` job in `.github/workflows/ci.yml`. Catches regressions like a missed zero-alloc path, a forgotten try-with-resources, or a new String/byte[] allocation per call.

## Build Notes

- **Java 21** target via `<maven.compiler.release>21</maven.compiler.release>`.
- **`-Xlint:all,-processing -Werror`** enabled globally — any new warning fails the build.
- **Spotless** auto-formats Java (Eclipse JDT), Markdown (Flexmark), POM XML (sortPom) on every build.
- **Eclipse/JDT in VSCode** auto-builds into `target/classes` and can overwrite Maven's output
  with broken stubs. If you see `Unresolved compilation problem` or `NoSuchMethodError` at runtime,
  delete `.project`/`.classpath`/`.settings` files from all modules and rebuild with `mvn clean install`.
- **JMH annotation processor** needs `<annotationProcessorPaths>` in maven-compiler-plugin.
- **ascopes protobuf-maven-plugin** config: uses `<protoc kind="binary-maven">` (not `<protocVersion>`).

## Module Layout

- **buff-json** — public API (`BuffJson`, `BuffJsonEncoder`, `BuffJsonDecoder`, `BuffJsonGeneratedEncoder`, `BuffJsonGeneratedDecoder`, `BuffJsonCodecHolder`) + internal serialization/deserialization
- **buff-json-protoc-plugin** — protoc plugin that generates `*JsonEncoder` and `*JsonDecoder` per message type, plus protoc insertion points that inject `BuffJsonCodecHolder` into generated message classes. Also bakes one JSON Schema resource per message (`META-INF/buff-json/schema/<fullName>.json`, by reusing `ProtobufSchema.generateJson()` at code-gen time — comments from `SourceCodeInfo`, constraints from a registered buf.validate `ExtensionRegistry`) — a plain resource file, with no code injected into protobuf's classes. Reads `CodeGeneratorRequest` from stdin, writes `CodeGeneratorResponse` to stdout. Build-time deps: `protobuf-java`, plus `buff-json-schema` (which transitively brings fastjson2) and `protovalidate` (used only during code generation; never runtime deps of the generated code). Built **after** `buff-json-schema` in the reactor.
- **buff-json-schema** — JSON Schema (draft 2020-12) generation from protobuf Descriptors. Depends on `protobuf-java` and `buff-json` (both provided) and `com.alibaba.fastjson2:fastjson2` (compile — used to parse the baked schema for the comment overlay and to serialize `generateJson()`), with optional `build.buf:protovalidate` for [buf.validate](https://buf.build/docs/protovalidate/) constraint mapping. `ProtobufSchema.generate(Descriptor)` returns `Map<String, Object>` (live walk, preserving exact `Integer`/`Long`/`Float`/`Double` types, with `description` fields overlaid from the baked schema); `ProtobufSchema.generateJson(Descriptor)` returns JSON **text** from the plugin-baked `META-INF/buff-json/schema/<fullName>.json` resource when present, else serialized live. Includes `title`, `description` (proto comments baked into the schema resource, or `SourceCodeInfo` for descriptors without one), `format` hints, `contentEncoding`, and [buf.validate](https://buf.build/docs/protovalidate/) constraints as JSON Schema keywords (minLength, pattern, format, minimum/maximum, minItems, required, etc.) when protovalidate is on the classpath. Ships `META-INF/native-image/.../resource-config.json` so the schema resources are included in GraalVM native images (one glob). `BakedSchema` loads the baked text; block-comment `*` prefixes are cleaned by `stripCommentLines`.
- **buff-json-swagger** — Swagger/OpenAPI `ModelConverter` that resolves protobuf `Message` types to OpenAPI 3.1 schemas. Implements `io.swagger.v3.core.converter.ModelConverter`, delegating to `ProtobufSchema.generate()` for schema generation and converting the `Map<String, Object>` result to swagger `Schema` objects. Handles `$defs`/`$ref` rewriting to `#/components/schemas/` with full proto names, `resolveAsRef` support, and all JSON Schema keywords including [buf.validate](https://buf.build/docs/protovalidate/) constraints. Depends on `buff-json-schema` (compile) and `swagger-core-jakarta`, `protobuf-java` (both provided). No auto-registration — requires explicit `ModelConverters.getInstance(true).addConverter(new ProtobufModelConverter())`.
- **buff-json-jackson** — Jackson `Module` wrapping `BuffJson.encode()`/`decode()` for `ObjectMapper` integration. Thin adapter (~3 classes), no reimplementation. Depends on `buff-json`, `jackson-databind`, `fastjson2`, `protobuf-java` (all provided). Provides `BuffJsonJacksonModule` (register with ObjectMapper). Protobuf messages work alongside POJOs/records in Jackson serialization. 38 tests including conformance, POJO/record integration, tree model, and roundtrip.
- **buff-json-tests** — conformance tests (each validates **all three paths**: codegen, typed-accessor, pure reflection) + JSON Schema tests + [buf.validate](https://buf.build/docs/protovalidate/) constraint tests + memory-leak reachability tests (`BuffJsonMemoryTest`) + own .proto definitions
- **buff-json-benchmarks** — JMH benchmarks (codegen vs typed-accessor vs JsonFormat vs Jackson-HubSpot vs BuffJsonJackson, UTF-16 and UTF-8 split) + own .proto definitions
- **buff-json-conformance** — testee for the **official protobuf conformance suite** (`conformance_test_runner`). Vendors `conformance.proto` + `test_messages_proto3.proto` from protobuf `v34.1`, runs the protoc plugin on them (codegen path under test), and shades a `conformance-testee.jar` (`ConformanceTestee`) that speaks the runner's framed stdin/stdout protocol. Covers only proto3 JSON (proto2/editions/text/jspb and binary↔binary are `skipped`); JSON via `BuffJson`, binary via protobuf-java. Run via `test-conformance.sh`; the testee's path is selectable with `BUFFJSON_PATH=codegen|runtime|reflection`. Wired into CI as a single-OS `conformance` job that runs the suite once per path (all three validated), report-only via `ENFORCE_CONFORMANCE=0` until `failure_list.txt` is curated. Not published.

Build order in reactor: core → protoc-plugin → schema → swagger → jackson → tests → benchmarks → conformance.
Each consumer module (tests, benchmarks, conformance) configures the protoc plugin via ascopes `protobuf-maven-plugin` `<jvmPlugin>`.

## Not Yet Implemented

- Streaming / Appendable output
- Proto2 support

