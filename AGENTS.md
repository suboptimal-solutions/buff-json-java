# AGENTS.md - buff-fastjson-java (root)

## Project Overview

Fast protobuf-to-JSON serializer for Java using fastjson2 as the JSON writing engine.
Up to ~10x faster than `JsonFormat.printer()` with the optional protoc plugin (~4-5x without).
Proto3 JSON spec compliant, including all 16 well-known types.
Includes JSON Schema generation from protobuf descriptors (separate module, no fastjson2 dependency).

## Architecture

Two encoding paths — codegen (fast) with fallback to generic (reflection):

```
BuffJSON.encode(message)
  -> Encoder.encode(message)
    -> sets ThreadLocal TypeRegistry + SKIP_GENERATED_ENCODERS (if configured)
    -> JSON.toJSONString(message)       # fastjson2 entry point
      -> ProtobufWriterModule.getObjectWriter()  # intercepts Message types
        -> ProtobufMessageWriter.writeFields()
          -> GeneratedEncoderRegistry.get()    # check for codegen encoder (ServiceLoader)
             -> if found: GeneratedEncoder.writeFields()   # direct typed accessors, no boxing
                -> nested messages: OtherEncoder.INSTANCE.writeFields()  # direct, no registry
                -> WKT Timestamp/Duration: writeTimestampDirect(seconds, nanos)  # no reflection
             -> if not:   generic path (MessageSchema + FieldWriter)  # reflection-style getField()
```

**Codegen path** (optional, ~2-3x faster): protoc plugin generates `*JsonEncoder` per message.
Each encoder calls typed getters directly (`msg.getId()` → `int`), eliminating:
- `message.getField(fd)` reflection + boxing
- `switch (fd.getJavaType())` runtime dispatch
- `ConcurrentHashMap.get()` for MessageSchema lookup

Additional codegen optimizations:
- **Direct nested encoder calls** — `AddressJsonEncoder.INSTANCE.writeFields()` instead of routing through `ProtobufMessageWriter` (avoids ThreadLocal read + ConcurrentHashMap lookup + instanceof check per nested message)
- **Inline WKT Timestamp/Duration** — `WellKnownTypes.writeTimestampDirect(jsonWriter, ts.getSeconds(), ts.getNanos())` bypasses descriptor string switch, field cache lookup, and `getField()` reflection+boxing
- **Pre-cached enum name arrays** — static `String[]` built at class init from enum descriptor values, replaces `forNumber()` + `getValueDescriptor().getName()` per write
- **String map key optimization** — avoids redundant `toString()` for String-typed map keys

**Generic path** (always available): iterates cached `FieldInfo[]`, dispatches by `JavaType`.
Still ~4-5x faster than `JsonFormat` due to schema caching and fastjson2 buffer reuse.

**Fallback**: `DynamicMessage` instances (e.g., from Any unpacking) always use the generic path.

fastjson2 handles: buffer pooling, number formatting, string escaping, UTF-8 encoding.
We handle: protobuf field extraction, proto3 JSON spec compliance, well-known types.

## Public API

```java
// Simple usage (no Any fields)
String json = BuffJSON.encode(message);

// Builder pattern with TypeRegistry (for Any fields)
Encoder encoder = BuffJSON.encoder()
    .withTypeRegistry(TypeRegistry.newBuilder()
        .add(MyMessage.getDescriptor())
        .build());
String json = encoder.encode(message);

// Force generic path (skip generated encoders, for benchmarking/testing)
Encoder genericEncoder = BuffJSON.encoder().withGeneratedEncoders(false);
String json = genericEncoder.encode(message);
```

- `BuffJSON` — static entry point + factory for `Encoder`
- `Encoder` — immutable, thread-safe, cacheable. Holds optional `TypeRegistry` and `useGeneratedEncoders` flag.
- `GeneratedEncoder<T>` — interface implemented by protoc-plugin-generated encoders. Discovered via `ServiceLoader`.

## Key Design Decisions

- **fastjson2 `ObjectWriterModule`**: Chose the public plugin API over depending on fastjson2 internals.
- **`MessageSchema` caching**: One-time cost per Descriptor. Avoids `getAllFields()` TreeMap allocation.
- **Pre-computed `char[] nameWithColon`**: Field names pre-encoded as `"name":` for `writeNameRaw(char[])`. Must use `char[]` (not `byte[]`) because `JSONWriterUTF16.writeNameRaw(byte[])` throws `UnsupportedOperation`.
- **`message.getField(descriptor)`** for field access in generic path (involves boxing for primitives).
- **`Float.floatToRawIntBits() == 0`** for default value checks (correctly handles `-0.0`).
- **`Long.toUnsignedString()`** for uint64, **`Integer.toUnsignedLong()`** for uint32.
- **ThreadLocal `TypeRegistry`** and **`SKIP_GENERATED_ENCODERS`** for per-encode configuration.
- **Builder pattern** (`Encoder`) mirrors `JsonFormat.printer()` style, extensible for future options.
- **`GeneratedEncoderRegistry`** uses `ServiceLoader` — zero-config discovery, no registration needed.
- **`DynamicMessage` guard**: Generated encoders are skipped for `DynamicMessage` instances (e.g., from Any unpacking) because they'd fail the cast to the concrete message type.
- **Protoc plugin generates to same package** as protobuf messages. `META-INF/services` file is also generated but needs a `<resources>` POM entry to be copied to `target/classes`.

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

- **buff-fastjson-core** — public API (`BuffJSON`, `Encoder`, `Decoder`, `GeneratedEncoder`, `GeneratedDecoder`, `GeneratedComments`) + internal serialization/deserialization
- **buff-fastjson-protoc-plugin** — protoc plugin that generates `*JsonEncoder`, `*JsonDecoder`, and `*Comments` per message/proto file. Depends only on `protobuf-java`. Reads `CodeGeneratorRequest` from stdin, writes `CodeGeneratorResponse` to stdout. The `*Comments` classes extract proto source comments from `SourceCodeInfo` (which protoc always sends to plugins) and make them available at runtime via `ServiceLoader`.
- **buff-protobuf-schema** — JSON Schema (draft 2020-12) generation from protobuf Descriptors. Depends on `protobuf-java` and `buff-fastjson-core` (both provided scope). `ProtobufSchema.generate(Descriptor)` returns `Map<String, Object>`. Includes `title`, `description` (from proto comments via `GeneratedComments` or `SourceCodeInfo`), `format` hints, and `contentEncoding`.
- **buff-fastjson-tests** — conformance tests (each validates both codegen and generic paths) + JSON Schema tests + own .proto definitions
- **buff-fastjson-benchmarks** — JMH benchmarks (3-way: codegen vs generic vs JsonFormat) + own .proto definitions

Build order in reactor: core → protoc-plugin → schema → tests → benchmarks.
Each consumer module (tests, benchmarks) configures the protoc plugin via ascopes `protobuf-maven-plugin` `<jvmPlugin>`.

## Not Yet Implemented

- Streaming / Appendable output
- Proto2 support

