# CLAUDE.md - buff-json-schema

## Module Purpose

Generates JSON Schema (draft 2020-12) from protobuf message Descriptors.
No fastjson2 dependency — only `protobuf-java` (provided scope).
Returns `Map<String, Object>` for maximum portability with OpenAPI 3.1+, AsyncAPI 3.0+, MCP, and any JSON library.

## Package Layout

```
io.suboptimal.buffjson.schema/
  ProtobufSchema.java            # Public API — ProtobufSchema.generate(Descriptor) / generate(Class)
  GeneratedCommentRegistry.java  # Comment registry populated via reflection from generated outer class static blocks (package-private)
  ValidateConstraints.java       # buf.validate constraint extraction → JSON Schema keywords (package-private)
```

## How It Works

Walks the protobuf `Descriptor` tree and maps each field to its Proto3 JSON Schema equivalent:

1. **Entry point**: `ProtobufSchema.generate(Descriptor)` creates an internal `ProtobufSchema` instance
   to track `$defs` and cycle detection state, then calls `schemaForMessage()`.
2. **Message → object**: Each message becomes `{"type": "object", "properties": {...}}`.
   Properties are keyed by `FieldDescriptor.getJsonName()` (camelCase / custom json_name).
3. **Field dispatch**: `schemaForField()` handles map → repeated → single value branching.
   `schemaForSingleValue()` switches on `JavaType` (INT, LONG, FLOAT, BOOLEAN, STRING, BYTE_STRING, ENUM, MESSAGE).
4. **Recursive types**: Tracked via `inProgress` set. When a cycle is detected, the type
   is placed in `$defs` and referenced via `{"$ref": "#/$defs/full.type.name"}`.
5. **Well-known types**: Detected by full name (same set of 16 as WellKnownTypes.java in core).
   Each maps to its canonical JSON Schema form (e.g., Timestamp → `{"type": "string", "format": "date-time"}`).
6. **Root schema**: Gets `"$schema": "https://json-schema.org/draft/2020-12/schema"` and
   `"$defs"` if any recursive types were encountered.
7. **Metadata enrichment**:
   - `title` on messages (from `descriptor.getName()`) and enums (from `enumDesc.getName()`)
   - `description` on WKTs with format info (e.g., Duration: "Signed seconds with up to 9 fractional digits, suffixed with 's'.")
   - `description` from proto comments (two sources, checked in order):
     1. `GeneratedCommentRegistry` — populated via reflection from generated protobuf outer class static initializers (protoc plugin extracts comments from `SourceCodeInfo` and registers them via `outer_class_scope` insertion point)
     2. `SourceCodeInfo` fallback — for descriptors loaded from `.desc` files with `--include_source_info`
   - `format` hints: `"int64"` / `"uint64"` on 64-bit string fields, `"date-time"` on Timestamp
   - `contentEncoding: "base64"` on bytes / BytesValue fields

## Proto3 JSON → JSON Schema Type Mapping

### Scalars

- int32, sint32, sfixed32 → `{"type": "integer"}`
- uint32, fixed32 → `{"type": "integer", "minimum": 0}`
- int64, sint64, sfixed64 → `{"type": "string", "format": "int64"}`
- uint64, fixed64 → `{"type": "string", "format": "uint64"}`
- float, double → `{"oneOf": [{"type": "number"}, {"type": "string", "enum": ["NaN", "Infinity", "-Infinity"]}]}`
- bool → `{"type": "boolean"}`
- string → `{"type": "string"}`
- bytes → `{"type": "string", "contentEncoding": "base64"}`
- enum → `{"type": "string", "title": "EnumName", "enum": ["VALUE1", ...]}`

#### `default` annotation for no-presence scalars

Singular **implicit-presence** scalar fields also carry a `"default"` set to the proto3 zero value an omitted property decodes back to — in its proto3 JSON form: `int→0`, `int64/uint64→"0"` (64-bit is quoted), `float/double→0.0`, `bool→false`, `string/bytes→""`, `enum→` the zero-value name (e.g. `"FOO_UNSPECIFIED"`). For enums the `default` sits beside the `$ref` (valid in draft 2020-12); the enum definition in `$defs` carries none. Gated by `defaultsWhenOmitted(fd)` = `!isRepeated && !hasPresence && !mapEntry`, applied once after the type switch in `schemaForSingleValue`. **Excluded** (no `default`): explicit presence (`optional`/oneof — absent means "unset"), repeated elements and map values (serialized even at the default), and message/WKT fields incl. wrappers like `BoolValue` (they have presence). `default` is a non-validating annotation, so it never affects whether a document validates. The swagger `ModelConverter` propagates it via `case "default" -> schema.setDefault(value)`.

### Composites

- repeated → `{"type": "array", "items": <element-schema>}`
- map<K,V> → `{"type": "object", "additionalProperties": <value-schema>}`
- oneof → all variants listed in properties (at most one present at runtime)

### Well-Known Types

- Timestamp → `{"type": "string", "format": "date-time", "description": "RFC 3339 date-time format."}`
- Duration → `{"type": "string", "description": "Signed seconds with up to 9 fractional digits, suffixed with 's'."}`
- FieldMask → `{"type": "string", "description": "Comma-separated camelCase field paths."}`
- Struct → `{"type": "object", "description": "Arbitrary JSON object."}`
- Value → `{"description": "Arbitrary JSON value."}` (any)
- ListValue → `{"type": "array", "description": "JSON array of arbitrary values."}`
- Any → `{"type": "object", ..., "description": "Arbitrary message identified by a type URL in @type."}`
- Int32Value → `{"type": "integer"}`, UInt32Value → `{"type": "integer", "minimum": 0}`
- Int64Value → `{"type": "string", "format": "int64"}`, UInt64Value → `{"type": "string", "format": "uint64"}`
- FloatValue, DoubleValue → float oneOf schema
- BoolValue → `{"type": "boolean"}`, StringValue → `{"type": "string"}`
- BytesValue → `{"type": "string", "contentEncoding": "base64"}`
- Empty → `{"type": "object"}`

## buf.validate Constraint Support

When `build.buf:protovalidate` is on the classpath (optional dependency), `ValidateConstraints` extracts
buf.validate field annotations and maps them to JSON Schema keywords. The feature activates automatically
via `Class.forName("build.buf.validate.ValidateProto")` checked once at class load time.

### Architecture

- `ProtobufSchema.VALIDATE_AVAILABLE` — static boolean, guards calls to `ValidateConstraints`
- `ValidateConstraints.extract(FieldDescriptor)` — reads `ValidateProto.field` extension from field options,
  dispatches by `FieldRules.getTypeCase()` to type-specific extractors, returns `FieldConstraints` record
- `ProtobufSchema.applyConstraints()` — merges constraints into the field schema, with special handling
  for float/double `oneOf` schemas (collapse to number when `finite`, otherwise constrain the number branch)
- `FieldConstraints` record — carries `schemaConstraints` (keyword map), `required` flag, `finite` flag,
  and `descriptionSuffix` (human-readable text for constraints without a JSON Schema equivalent)

### Constraint → JSON Schema Mapping

|          buf.validate constraint           |                       JSON Schema keyword                       |
|--------------------------------------------|-----------------------------------------------------------------|
| `string.min_len` / `max_len` / `len`       | `minLength` / `maxLength`                                       |
| `string.pattern`                           | `pattern`                                                       |
| `string.const`                             | `const`                                                         |
| `string.in`                                | `enum`                                                          |
| `string.email/hostname/ipv4/ipv6/uri/uuid` | `format`                                                        |
| `int32.gt` / `gte` / `lt` / `lte`          | `exclusiveMinimum` / `minimum` / `exclusiveMaximum` / `maximum` |
| `int64.*` (all constraints)                | description text (JSON representation is a string)              |
| `float/double.finite`                      | collapses `oneOf` to `{"type": "number"}`                       |
| `float/double.gte/lte/gt/lt`               | constraints on the `number` branch of `oneOf`                   |
| `repeated.min_items/max_items`             | `minItems` / `maxItems`                                         |
| `repeated.unique`                          | `uniqueItems`                                                   |
| `map.min_pairs/max_pairs`                  | `minProperties` / `maxProperties`                               |
| `field.required`                           | adds field to parent `required` array                           |
| `prefix/suffix/contains/not_in/CEL`        | description text (no JSON Schema equivalent)                    |

### Key Design Decisions

- Uses protobuf descriptor reflection (`Message.getField()`, `getDescriptorForType().findFieldByName()`)
  for numeric rule types to avoid duplicating code across 10+ integer/float types
- 64-bit numeric constraints go to description text because proto3 JSON represents them as strings
- Float/double `oneOf` restructuring: `finite` collapses to plain `number`, otherwise constraints
  go on the `number` branch and the string branch (NaN/Infinity) is preserved

## Dependencies

- `com.google.protobuf:protobuf-java` (provided) — Descriptor, FieldDescriptor, EnumDescriptor, FileDescriptor, DescriptorProtos, SourceCodeInfo, Message
- `io.github.suboptimal-solutions:buff-json` (provided) — BuffJsonCodecHolder interface
- `build.buf:protovalidate` (optional) — buf.validate constraint types (FieldRules, StringRules, ValidateProto)

