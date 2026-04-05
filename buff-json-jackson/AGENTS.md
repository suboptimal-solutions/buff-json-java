# AGENTS.md - buff-json-jackson

## Module Purpose

Jackson `Module` that enables proto3 JSON serialization/deserialization of protobuf messages
through Jackson's `ObjectMapper`. Thin wrapper around `BuffJson.encode()`/`decode()` — not a
reimplementation. Protobuf messages work seamlessly alongside POJOs and Java records.

## Package Layout

```
io.suboptimal.buffjson.jackson/
  ProtobufJacksonModule.java       # Jackson Module — registers serializers/deserializers
  ProtobufMessageSerializer.java   # JsonSerializer<Message> — wraps BuffJsonEncoder.encode()
  ProtobufMessageDeserializer.java # JsonDeserializer<Message> — wraps BuffJsonDecoder.decode()
```

## How It Works

1. **Serialization**: `ProtobufMessageSerializer.serialize()` calls `BuffJsonEncoder.encode(message)` to
   get the proto3 JSON string, then writes it via `JsonGenerator.writeRawValue(json)`.
2. **Deserialization**: `ProtobufMessageDeserializer.deserialize()` reads the JSON subtree as a
   `JsonNode`, converts to string via `toString()`, then calls `BuffJsonDecoder.decode(json, targetClass)`.
3. **Type resolution**: `ProtobufSerializers` (extends `Serializers.Base`) returns the serializer
   for any `Message` subclass. `ProtobufDeserializers` (extends `Deserializers.Base`) creates a
   type-specific deserializer per `Message` subclass.
4. **TypeRegistry**: Optional constructor parameter for `google.protobuf.Any` support. Passed
   through to the underlying `BuffJsonEncoder`/`BuffJsonDecoder`.

## Design Decisions

- **Wrapper, not reimplementation**: Delegates to `BuffJson`'s fastjson2-based engine. No
  Jackson-specific field writers/readers — avoids code duplication and guarantees identical output.
- **`writeRawValue`**: The serialized JSON from `BuffJson.encode()` is written as raw JSON into
  Jackson's output. This means `ObjectMapper.valueToTree()` doesn't produce a structured tree
  for proto messages — use `writeValueAsString` + `readTree` instead.
- **No generated encoder support**: Jackson users accept Jackson's overhead. The runtime path
  through `BuffJson` (which itself uses generated encoders when available) is sufficient.
- **Provided scope dependencies**: `jackson-databind`, `fastjson2`, and `protobuf-java` are all
  `provided` — the user's project supplies them.

## Tests

38 tests in two test classes:

### JacksonProto3ConformanceTest (27 tests)

- **ScalarTests** (4): all types, default values, NaN/Infinity
- **ComplexMessageTests** (7): nested, repeated, maps, oneof, enums
- **WellKnownTypeTests** (8): Timestamp, Duration, FieldMask, Struct/Value/ListValue, wrappers, Empty, Any
- **OptionalAndCustomTests** (4): explicit presence, custom json_name, recursive
- **RoundtripTests** (4): scalar, complex, WKT roundtrips + cross-library compatibility

### JacksonPojoIntegrationTest (11 tests)

- **RecordWithProtoField** (3): record containing proto, proto list, mixed record
- **PojoWithProtoField** (1): POJO class with proto field
- **TokenParserAndTreeModel** (3): readTree→treeToValue, proto→tree, generic list
- **WellKnownTypesInPojo** (1): record with WKT field (Timestamp as RFC3339)
- **EdgeCases** (3): null proto, empty proto, proto with bytes

## Dependencies

- `com.fasterxml.jackson.core:jackson-databind` (provided) — ObjectMapper, Module, JsonSerializer, JsonDeserializer
- `com.alibaba.fastjson2:fastjson2` (provided) — runtime dependency of buff-json
- `com.google.protobuf:protobuf-java` (provided) — Message types
- `io.github.suboptimal-solutions:buff-json` (compile) — BuffJson, BuffJsonEncoder, BuffJsonDecoder

