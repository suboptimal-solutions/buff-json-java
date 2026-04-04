# AGENTS.md - buff-fastjson-protoc-plugin

## Module Purpose

Protoc plugin that generates optimized `*JsonEncoder`, `*JsonDecoder`, and `*Comments` classes.
Generated encoders use typed accessors directly (`msg.getId()` returns `int`) instead of
`message.getField(fd)` (which returns `Object` and boxes primitives), eliminating boxing,
runtime type dispatch, and schema cache lookups. Generated comment classes extract proto
source comments from `SourceCodeInfo` (which protoc always provides to plugins even without
`--include_source_info`) and make them available at runtime for JSON Schema `description` fields.

## How It Works

Standard protoc plugin protocol: reads `CodeGeneratorRequest` from stdin, writes
`CodeGeneratorResponse` to stdout. Invoked by protoc via the ascopes protobuf-maven-plugin
`<jvmPlugin>` configuration.

## Key Classes

- `BuffJsonProtocPlugin.java` — main entry point, builds `FileDescriptor` graph, orchestrates generation
- `EncoderGenerator.java` — generates one `*JsonEncoder` class per message type
- `DecoderGenerator.java` — generates one `*JsonDecoder` class per message type
- `CommentGenerator.java` — generates one `*Comments` class per proto file (extracting comments from `SourceCodeInfo`)

## What Gets Generated

For each non-WKT, non-map-entry message type:

1. A `FooJsonEncoder.java` class implementing `GeneratedEncoder<Foo>`
2. A `public static final INSTANCE` singleton for direct calls from other encoders
3. Pre-computed `char[] NAME_*` constants for each field (format: `"fieldName":`)
4. Pre-cached `String[] ENUM_*_NAMES` arrays for each enum type (built from enum descriptor at class init, avoiding `UNRECOGNIZED` which throws from `getNumber()`)
5. A `writeFields()` method with inlined per-field encoding logic
6. A `META-INF/services/io.suboptimal.buffjson.GeneratedEncoder` file listing all encoders
7. A `*Comments.java` class per proto file implementing `GeneratedComments` with a `Map<String, String>` of proto full name → leading comment
8. A `META-INF/services/io.suboptimal.buffjson.GeneratedComments` file listing all comment providers

## Field Handling

|         Category         |                                 Generated pattern                                  |
|--------------------------|------------------------------------------------------------------------------------|
| Scalar (no presence)     | `int v = msg.getId(); if (v != 0) { writeNameRaw; writeInt32(v); }`                |
| Scalar (optional)        | `if (msg.hasId()) { writeNameRaw; writeInt32(msg.getId()); }`                      |
| uint32/fixed32           | `writeInt64(Integer.toUnsignedLong(...))`                                          |
| int64 variants           | `writeString(Long.toString(...))`                                                  |
| uint64/fixed64           | `writeString(Long.toUnsignedString(...))`                                          |
| float/double             | Inline NaN/Infinity check                                                          |
| Enum                     | Static `ENUM_*_NAMES` array lookup by `msg.getStatusValue()` (no `forNumber()`)    |
| bytes                    | `Base64.getEncoder().encodeToString(v.toByteArray())`                              |
| Repeated                 | `msg.getFooList()`, check isEmpty, iterate                                         |
| Map (String key)         | `msg.getFooMap()`, iterate, `entry.getKey()` directly (no `toString()`)            |
| Map (non-String key)     | `msg.getFooMap()`, iterate, `entry.getKey().toString()`                            |
| Oneof                    | `switch (msg.getFooCase())` with per-case typed accessor                           |
| Nested message (non-WKT) | `FooJsonEncoder.INSTANCE.writeFields()` — direct call, bypasses registry           |
| Nested message (WKT)     | `WellKnownTypes.write(jsonWriter, nested)`                                         |
| Timestamp                | `WellKnownTypes.writeTimestampDirect(jsonWriter, ts.getSeconds(), ts.getNanos())`  |
| Duration                 | `WellKnownTypes.writeDurationDirect(jsonWriter, dur.getSeconds(), dur.getNanos())` |

## Name Resolution

- Proto full name → Java class name mapping built from `FileDescriptorProto` options
- Respects `java_package`, `java_multiple_files`, `java_outer_classname`
- Nested messages use parent class as prefix: `Outer.Inner`
- Encoder class names flatten nesting: `Outer_InnerJsonEncoder`
- `protoToEncoderClass` mapping pre-computed for all messages in `filesToGenerate` so generated encoders can reference each other directly via `INSTANCE.writeFields()` (bypasses runtime registry)

## Important Edge Cases

- **`google.protobuf.Empty`** is NOT in the WKT set — it serializes as a regular empty message `{}`
- **`DynamicMessage`** cannot use generated encoders (would fail cast) — guarded in `ProtobufMessageWriter`
- **Map entry types** (`options.map_entry = true`) are skipped — they're synthetic
- **`writeNameRaw(char[])`** must be used (not `byte[]`) — `JSONWriterUTF16.writeNameRaw(byte[])` throws `UnsupportedOperation`
- **Enum `UNRECOGNIZED`** — protobuf's generated `UNRECOGNIZED` constant throws `IllegalArgumentException` from `getNumber()`. Enum name arrays use `EnumDescriptor.getValues()` (not Java `.values()`) to avoid this
- **Cross-file nested encoder calls** — `protoToEncoderClass` only contains messages from `filesToGenerate`. If a nested message is defined in a non-generated file, the fallback to `ProtobufMessageWriter.INSTANCE.writeMessage()` is used (which still finds the encoder at runtime via the registry)

## Build

- Depends only on `protobuf-java` (for `CodeGeneratorRequest`/`CodeGeneratorResponse` and descriptor APIs)
- No shading needed — ascopes plugin resolves classpath automatically
- Must be built before consumer modules (listed before tests/benchmarks in parent POM)

## Dependencies

- `com.google.protobuf:protobuf-java` — CodeGeneratorRequest, FileDescriptor, FieldDescriptor

