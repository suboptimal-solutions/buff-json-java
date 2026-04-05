# AGENTS.md - buff-json

## Module Purpose

The core library. Contains the public API (`BuffJson`, `BuffJsonEncoder`, `BuffJsonDecoder`) and all internal serialization logic.
No dependency on specific `.proto` definitions — works with any `com.google.protobuf.Message`.

## Package Layout

```
io.suboptimal.buffjson/
  BuffJson.java                    # Static entry point + factory: BuffJson.encoder(), BuffJson.decoder()
  BuffJsonEncoder.java             # Configurable encoder — creates JSONWriter directly, exposes writerModule()
  BuffJsonDecoder.java             # Configurable decoder — creates JSONReader directly, exposes readerModule()
  BuffJsonGeneratedEncoder.java    # Interface for protoc-plugin-generated encoders (ServiceLoader discovered)
  BuffJsonGeneratedDecoder.java    # Interface for protoc-plugin-generated decoders (ServiceLoader discovered)
  BuffJsonGeneratedComments.java   # Interface for protoc-plugin-generated comment providers (ServiceLoader discovered)

io.suboptimal.buffjson.internal/
  ProtobufWriterModule.java        # fastjson2 ObjectWriterModule (intercepts Message types) — for mixed pojo+proto usage
  ProtobufReaderModule.java        # fastjson2 ObjectReaderModule (intercepts Message types) — for mixed pojo+proto usage
  ProtobufMessageWriter.java       # Stateful instance holding TypeRegistry + useGenerated. Main serialization logic.
  ProtobufMessageReader.java       # Stateful instance holding TypeRegistry + useGenerated. Main deserialization logic.
  GeneratedEncoderRegistry.java    # ConcurrentHashMap<String, BuffJsonGeneratedEncoder> populated via ServiceLoader
  GeneratedDecoderRegistry.java    # ConcurrentHashMap<String, BuffJsonGeneratedDecoder> populated via ServiceLoader
  MessageSchema.java               # Cached field metadata per Descriptor (runtime path)
  FieldWriter.java                 # Type-dispatched value writing (scalars, maps, repeated) (runtime path)
  FieldReader.java                 # Type-dispatched value reading (scalars, maps, repeated) (runtime path)
  WellKnownTypes.java              # Special handling for 16 well-known protobuf types
                                   #   writeTimestampDirect()/writeDurationDirect() accept
                                   #   primitives directly — used by codegen to bypass
                                   #   descriptor lookup and getField() reflection
```

## Serialization Flow (hot path)

1. `BuffJsonEncoder.encode(message)`:
   - Creates `JSONWriter` directly via `JSONWriter.of()` (bypasses fastjson2 module dispatch)
   - Creates `ProtobufMessageWriter(typeRegistry, useGenerated)` with encoder's settings
   - Calls `writer.writeMessage(jsonWriter, message)`
2. `ProtobufMessageWriter.writeFields(jsonWriter, message)` — instance method:
   - First checks `GeneratedEncoderRegistry.get(descriptor)`:
     - Skipped if `this.useGenerated` is false (for benchmarking)
     - Skipped for `DynamicMessage` instances (would fail cast to concrete type)
     - If found: delegates to `BuffJsonGeneratedEncoder.writeFields(jw, msg, this)` → **codegen path**
       - Nested messages call other encoders directly via `INSTANCE.writeFields(jw, msg, writer)` (no registry)
       - Timestamp/Duration fields call `writeTimestampDirect()`/`writeDurationDirect()`
       - Enum fields use pre-cached `String[]` name arrays
     - (done — never falls through to runtime path)
   - If no generated encoder: **runtime path**:
     - Iterates cached `FieldInfo[]` array (no `getAllFields()` TreeMap)
     - For each field: checks presence/default, then calls `FieldWriter.writeValue(jw, fd, value, this)`
3. `FieldWriter.writeValue()` dispatches on `JavaType` (INT, LONG, FLOAT, ..., MESSAGE)
4. For MESSAGE fields: checks `WellKnownTypes.isWellKnownType()` first, then recurses via `writer.writeMessage()`

## Settings Flow (no ThreadLocals)

- `ProtobufMessageWriter` and `ProtobufMessageReader` hold settings as instance fields: `TypeRegistry typeRegistry`, `boolean useGenerated`
- Settings propagate through the call chain by passing the writer/reader instance (`this`) to all methods that need them
- `FieldWriter` / `FieldReader` receive the writer/reader as a parameter for recursive nested message writes/reads
- `WellKnownTypes.write(jw, msg, writer)` / `readWkt(reader, desc, msgReader)` receive the writer/reader for Any type support
- Generated encoders/decoders receive the writer/reader: `writeFields(jw, msg, writer)` / `readMessage(reader, msgReader)`

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
- **uint64/fixed64**: `Long.toUnsignedString()` for unsigned quoted strings
- **int64 and all 64-bit types**: Must be quoted strings in JSON
- **NaN/Infinity**: fastjson2 writes `null` — we intercept and write quoted strings
- **-0.0**: Use `floatToRawIntBits()`/`doubleToRawLongBits()` (not `==`) for default checks
- **Enum in map values**: `message.getField()` returns `Integer` (not `EnumValueDescriptor`) for map entries
- **Wrapper types**: Serialize as unwrapped primitive values, not objects
- **FieldMask**: `snake_case` → `lowerCamelCase` conversion, comma-joined
- **Struct/Value/ListValue**: Serialize as native JSON objects/arrays/values
- **Duration nanos**: Format to 3, 6, or 9 digits (not arbitrary precision)
- **Any**: Requires TypeRegistry. Regular messages: `{"@type":..., ...fields}`. WKTs: `{"@type":..., "value":...}`

## Dependencies

- `com.google.protobuf:protobuf-java` — Message, Descriptor, TypeRegistry, DynamicMessage
- `com.alibaba.fastjson2:fastjson2` — JSONWriter, JSONReader, ObjectWriterModule, ObjectReaderModule

