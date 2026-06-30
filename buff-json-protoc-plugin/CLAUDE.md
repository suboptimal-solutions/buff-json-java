# CLAUDE.md - buff-json-protoc-plugin

## Module Purpose

Protoc plugin that generates optimized `*JsonEncoder` and `*JsonDecoder` classes, plus protoc
insertion points that inject `BuffJsonCodecHolder` into generated message classes.
Generated encoders use typed accessors directly (`msg.getId()` returns `int`) instead of
`message.getField(fd)` (which returns `Object` and boxes primitives), eliminating boxing,
runtime type dispatch, and schema cache lookups. The plugin also bakes one **JSON Schema
resource** per message at `META-INF/buff-json/schema/<fullName>.json` (no code injected into
protobuf's classes, so generated messages keep depending only on `protobuf-java`), built by
reusing `ProtobufSchema` at code-gen time — proto comments from `SourceCodeInfo` (which protoc
always provides to plugins) and buf.validate constraints surfaced via a registered
`ExtensionRegistry`. Comments live **only** in this baked schema; there is no separate comments
resource.

## How It Works

Standard protoc plugin protocol: reads `CodeGeneratorRequest` from stdin, writes
`CodeGeneratorResponse` to stdout. Invoked by protoc via the ascopes protobuf-maven-plugin
`<jvmPlugin>` configuration.

## Key Classes

- `BuffJsonProtocPlugin.java` — main entry point, builds `FileDescriptor` graph, orchestrates generation
- `EncoderGenerator.java` — generates one `*JsonEncoder` class per message type
- `DecoderGenerator.java` — generates one `*JsonDecoder` class per message type
- JSON Schema baking — `BuffJsonProtocPlugin.generateSchemaResources(...)` calls `ProtobufSchema.generateJson(descriptor)` (from `buff-json-schema`, a build-time dep) per message and writes the result to a `.json` resource. Comments come from `SourceCodeInfo` (present at build time) through `ProtobufSchema`; constraints from the `buf.validate` `ExtensionRegistry` wired in `buildValidateRegistry()` + `internalUpdateFileDescriptor`

## What Gets Generated

For each non-WKT, non-map-entry message type:

1. A `FooJsonEncoder.java` class implementing `BuffJsonGeneratedEncoder<Foo>`
2. A `public static final INSTANCE` singleton for direct calls from other encoders
3. Pre-computed name constants per field — both `char[] NAME_*` (UTF-16 path) and `byte[] NAME_*_BYTES` (UTF-8 path), populated from `nameChars(...)` / `nameBytes(...)` helpers at class init. ASCII-only.
4. Pre-cached `String[] ENUM_*_NAMES` arrays for each enum type (built from enum descriptor at class init, avoiding `UNRECOGNIZED` which throws from `getNumber()`)
5. A `writeFields(JSONWriter, T, ProtobufMessageWriter)` method with inlined per-field encoding logic, opening with `boolean utf8 = jsonWriter.isUTF8();` so each field-name write dispatches via `if (utf8) writeNameRaw(NAME_X_BYTES); else writeNameRaw(NAME_X);`
6. A `message_implements` insertion point per message adding `BuffJsonCodecHolder` to the implements clause
7. A `class_scope` insertion point per message adding `buffJsonEncoder()`/`buffJsonDecoder()` method implementations
8. A `META-INF/buff-json/schema/<fullName>.json` resource per message (the baked JSON Schema, comments and buf.validate constraints included) — read at runtime by `buff-json-schema`, with nothing injected into the generated protobuf classes

## Field Handling

|         Category         |                                      Generated pattern                                      |
|--------------------------|---------------------------------------------------------------------------------------------|
| Scalar (no presence)     | `int v = msg.getId(); if (v != 0) { emitWriteName; writeInt32(v); }`                        |
| Scalar (optional)        | `if (msg.hasId()) { emitWriteName; writeInt32(msg.getId()); }`                              |
| uint32/fixed32           | `writeInt64(Integer.toUnsignedLong(...))`                                                   |
| int64 variants           | `writeString(...)` — no `Long.toString()` allocation, fastjson2 unboxes                     |
| uint64/fixed64           | `WellKnownTypes.writeUnsignedLongString(jsonWriter, ...)` — no String allocation            |
| float/double             | Inline NaN/Infinity check, `isFinite()` first (hot path)                                    |
| Enum                     | Static `ENUM_*_NAMES` array lookup by `msg.getStatusValue()` (no `forNumber()`)             |
| bytes                    | `jsonWriter.writeBase64(v.toByteArray())` — fastjson2 encodes directly into buffer          |
| Field name               | `if (utf8) writeNameRaw(NAME_X_BYTES); else writeNameRaw(NAME_X);` — JIT-specialized branch |
| Repeated                 | `msg.getFooList()`, check isEmpty, iterate                                                  |
| Map (String key)         | `msg.getFooMap()`, iterate, `entry.getKey()` directly (no `toString()`)                     |
| Map (non-String key)     | `msg.getFooMap()`, iterate, `entry.getKey().toString()`                                     |
| Oneof                    | `switch (msg.getFooCase())` with per-case typed accessor                                    |
| Nested message (non-WKT) | `FooJsonEncoder.INSTANCE.writeFields(jw, nested, writer)` — direct call, bypasses registry  |
| Nested message (WKT)     | `WellKnownTypes.write(jsonWriter, nested, writer)`                                          |
| Timestamp                | `WellKnownTypes.writeTimestampDirect(jsonWriter, ts.getSeconds(), ts.getNanos())`           |
| Duration                 | `WellKnownTypes.writeDurationDirect(jsonWriter, dur.getSeconds(), dur.getNanos())`          |

## Name Resolution

- Proto full name → Java class name mapping built from `FileDescriptorProto` options
- Respects `java_package`, `java_multiple_files`, `java_outer_classname`
- Nested messages use parent class as prefix: `Outer.Inner`
- Encoder class names flatten nesting: `Outer_InnerJsonEncoder`
- `protoToEncoderClass` mapping pre-computed for all messages in `filesToGenerate` so generated encoders can reference each other directly via `INSTANCE.writeFields(jw, msg, writer)` (bypasses runtime registry)

## Important Edge Cases

- **`google.protobuf.Empty`** is NOT in the WKT set — it serializes as a regular empty message `{}`
- **`DynamicMessage`** cannot use generated encoders (would fail cast) — guarded in `ProtobufMessageWriter`
- **Map entry types** (`options.map_entry = true`) are skipped — they're synthetic
- **`writeNameRaw(byte[])` throws `UnsupportedOperation` on `JSONWriterUTF16`** — generated code emits both `NAME_X` (char[]) and `NAME_X_BYTES` (byte[]) and dispatches on `boolean utf8 = jsonWriter.isUTF8()` hoisted at the top of `writeFields`. Helper: `EncoderGenerator.emitWriteName(sb, constName, indent)`.
- **Enum `UNRECOGNIZED`** — protobuf's generated `UNRECOGNIZED` constant throws `IllegalArgumentException` from `getNumber()`. Enum name arrays use `EnumDescriptor.getValues()` (not Java `.values()`) to avoid this
- **Multiple distinct enum types per message** — each enum's name-array initializer is wrapped in its own `{ }` block inside the single `static {}` so the `edVals`/`max` locals don't collide (a message referencing two different enum types previously failed to compile)
- **Negative enum values** (e.g. `NEG = -1`) — can't index the `String[]` name array, so they're skipped during array fill (`if (v.getNumber() >= 0)`, avoids `ArrayIndexOutOfBounds` at class init) and resolved at the write site via a descriptor fallback (`EnumClass.getDescriptor().findValueByNumber(n)`) so a named negative value still serializes by name; only genuinely unknown numbers fall through to the integer
- **Accessor naming with digits** — `BuffJsonProtocPlugin.toCamelCase` mirrors protobuf's `UnderscoresToCamelCase`: a digit forces the next letter to be capitalized (`field0name5` → `getField0Name5`), matching protobuf-java's generated getters/setters
- **Underscored oneof names** — the oneof case enum's not-set constant is `toCamelCase(oneofName).toUpperCase() + "_NOT_SET"` (`oneof_field` → `ONEOFFIELD_NOT_SET`), not the raw snake_case uppercased; the per-field case values keep underscores (`oneof_uint32` → `ONEOF_UINT32`)
- **Field ordering** — `EncoderGenerator` emits fields in descriptor (field-number) order, placing each oneof's `switch` at the position of its first-declared member (not bunched at the end), so output matches `JsonFormat`'s field-number order
- **`google.protobuf.NullValue`** — serialized as JSON `null` (not the enum name `"NULL_VALUE"`): `EncoderGenerator.writeEnumValue` emits `jsonWriter.writeNull()` for the NullValue enum type. On decode, `DecoderGenerator` treats a JSON `null` for a NullValue field as `NULL_VALUE` via `setXxxValue(0)` (which also marks a oneof case as set); messages containing a NullValue or Value field are "null-sensitive" and skip the blanket null-skip so each field decides
- **Cross-file nested encoder calls** — `protoToEncoderClass` only contains messages from `filesToGenerate`. If a nested message is defined in a non-generated file, the fallback to `writer.writeMessage(jsonWriter, nested)` is used (which still finds the encoder at runtime via `instanceof BuffJsonCodecHolder`)
- **Insertion point file paths** — for `java_multiple_files = true`, message insertion points target `package/MessageName.java`; for `false`, they target `package/OuterClassName.java`. The `outer_class_scope` insertion point always targets the outer class file
- **Block comments** (`/** */`) — the `*` prefix on each line is stripped by `ProtobufSchema.stripCommentLines()` (in `buff-json-schema`) when it reads `SourceCodeInfo` at bake time, producing clean multiline text
- **Generated decoders route fallible parses through `FieldReader`** — `DecoderGenerator` emits `FieldReader.readStrictInt32(reader)`/`readStrictUint32(reader)` for int32/uint32/fixed32, `FieldReader.readStrictString(reader)` for string fields, `FieldReader.readBytes(reader)` for bytes, `FieldReader.enumNumber(reader, EnumClass.getDescriptor(), name)` for enum names, and `FieldReader.parseIntKey`/`parseUnsignedIntKey`/`parseLongKey`/`parseUnsignedLongKey(reader, keyStr)` for numeric map keys — never inline `reader.readInt32Value()`/`readString()`/`BASE64.decode`/`Enum.valueOf`/`Long.parseLong`. This gives generated code the same `JSONException`-for-bad-input contract **and** the same proto3 strictness (rejecting `1.5`, out-of-range, empty/wrong-type) as the runtime path (see buff-json `Error Contract` / `Decoder Input Hardening`); the helpers are `public` because generated code lives in the user's package

## Build

- Build-time deps: `protobuf-java` (CodeGeneratorRequest/descriptor APIs), `buff-json-schema` (reused to bake JSON Schema resources), and `protovalidate` (so baked schemas carry buf.validate constraints). These are **code-generation-time only** — they never become runtime dependencies of the generated code.
- No shading needed — the ascopes `jvm-maven` plugin resolves the plugin's transitive deps onto the code-gen classpath automatically (verified: `buff-json-schema` + `protovalidate` load during `generate`).
- Built **after** `buff-json-schema` in the reactor (it now depends on it). Still built before the consumer modules (tests/benchmarks/conformance).

## Dependencies

- `com.google.protobuf:protobuf-java` — CodeGeneratorRequest, FileDescriptor, FieldDescriptor, ExtensionRegistry
- `io.github.suboptimal-solutions:buff-json-schema` — `ProtobufSchema.generateJson(...)` for baking schema resources (build-time)
- `build.buf:protovalidate` — buf.validate extensions registered into the parse `ExtensionRegistry` so constraints reach `ProtobufSchema` (build-time)

