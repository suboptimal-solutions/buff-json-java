# Buff JSON (Java)

Blazingly fast ⚡️ JSON serialization for Protocol Buffer messages in Java, compliant with the [Proto3 JSON spec](https://protobuf.dev/programming-guides/proto3/#json).

## Performance

Up to **~15x** faster than `JsonFormat.printer().print()` from `protobuf-java-util`.

Up to **~7x** faster than `jackson.writeValueAsString()` from `jackson-datatype-protobuf`.

Encode throughput, higher is better:

|              Message type               | JsonFormat (ops/s) | Jackson (ops/s) | BuffJson runtime (ops/s) | BuffJson compile (ops/s) | Compile vs JsonFormat | Compile vs Jackson |
|-----------------------------------------|-------------------:|----------------:|-------------------------:|-------------------------:|:---------------------:|:------------------:|
| SimpleMessage (6 fields)                |              1.81M |           2.72M |                   16.84M |                   19.51M |      **~10.8x**       |     **~7.2x**      |
| ComplexMessage (nested, maps, repeated) |               138K |            277K |                    1.41M |                    2.06M |      **~14.9x**       |     **~7.4x**      |

Benchmarked on JDK 21 (Corretto) with JMH on Apple Silicon. Run `./run-benchmarks.sh` to reproduce on your environment.

## How it works

Uses [Alibaba fastjson2](https://github.com/alibaba/fastjson2) as the JSON writing engine. The encoder creates a `JSONWriter` directly and calls `ProtobufMessageWriter.writeMessage()` — bypassing fastjson2's module dispatch and provider lookup. All JSON formatting (buffering, number encoding, string escaping) is delegated to fastjson2's optimized infrastructure.

**Codegen path** (optional protoc plugin):
- **Direct typed accessors** — `message.getId()` returns `int`, no boxing
- **No runtime type dispatch** — each field's encoding logic is inlined at compile time
- **No schema cache lookup** — field iteration order and names are hardcoded
- **Direct nested encoder calls** — nested messages call `INSTANCE.writeFields()` directly
- **Inline WKT Timestamp/Duration** — typed accessor calls (`ts.getSeconds()`, `ts.getNanos()`) bypass descriptor lookup and reflection
- **Pre-cached enum names** — static `String[]` array lookup, no `forNumber()` or descriptor calls
- **Zero-alloc int64/bytes** — `writeString((long) v)`, `writeBase64(v.toByteArray())` — no intermediate String
- **Pre-encoded UTF-8 byte[] field names** — `if (utf8) writeNameRaw(byte[]); else writeNameRaw(char[]);` per field, JIT-specialized

**Typed-accessor runtime** (default fallback):
- **No `getAllFields()` / TreeMap allocation** per call
- **No `getField()` reflection** — `LambdaMetafactory` binds typed lambdas once per Descriptor, cached
- **Specialized repeated/map accessors** — `RepeatedInt/Long/String/Message`, `TypedMap` eliminate per-element type-dispatch switch
- **Same UTF-8 byte[] field name pre-encoding** as codegen, via `MessageSchema.FieldInfo.nameWithColonUtf8`
- **Zero-allocation timestamps** — epoch→calendar conversion via integer arithmetic (Howard Hinnant's civil calendar algorithm), exact-size byte buffers
- **fastjson2 striped buffer reuse** eliminates per-call buffer allocations

Inspired by [fastjson2](https://github.com/alibaba/fastjson2) and [buffa](https://github.com/anthropics/buffa).

## Usage

### Basic (runtime path, no build changes)

```java
import io.suboptimal.buffjson.BuffJson;

BuffJsonEncoder encoder = BuffJson.encoder();
String json = encoder.encode(myProtoMessage);
byte[] bytes = encoder.encodeToBytes(myProtoMessage);
encoder.encode(myProtoMessage, outputStream);
```

### With Any type support

```java
BuffJsonEncoder encoder = BuffJson.encoder()
    .setTypeRegistry(TypeRegistry.newBuilder()
        .add(MyMessage.getDescriptor())
        .build());
String json = encoder.encode(messageContainingAny);
```

### With protoc plugin (optional, ~2x faster)

Add the plugin to your protobuf-maven-plugin configuration:

```xml
<plugin>
    <groupId>io.github.ascopes</groupId>
    <artifactId>protobuf-maven-plugin</artifactId>
    <configuration>
        <plugins>
            <plugin kind="jvm-maven">
                <groupId>io.suboptimal</groupId>
                <artifactId>buff-json-protoc-plugin</artifactId>
                <version>${buff-json.version}</version>
                <mainClass>io.suboptimal.buffjson.protoc.BuffJsonProtocPlugin</mainClass>
            </plugin>
        </plugins>
    </configuration>
</plugin>
```

No code changes needed — the plugin uses protoc insertion points to inject codec discovery directly into the generated message classes. If the plugin is not configured, the runtime reflection path is used.

The serialization output matches `JsonFormat.printer().omittingInsignificantWhitespace().print()` exactly.

### Deserialization (JSON to protobuf)

```java
BuffJsonDecoder decoder = BuffJson.decoder();
MyMessage msg = decoder.decode(json, MyMessage.class);
MyMessage msg = decoder.decode(bytes, MyMessage.class);
MyMessage msg = decoder.decode(inputStream, MyMessage.class);

// With Any type support
BuffJsonDecoder decoder = BuffJson.decoder()
    .setTypeRegistry(TypeRegistry.newBuilder()
        .add(MyMessage.getDescriptor())
        .build());
MyMessage msg = decoder.decode(json, MyMessage.class);
```

### Mixed pojo + protobuf (fastjson2 registration)

For projects that use `JSON.toJSONString()` with both POJOs and protobuf messages, register fastjson2 modules from the encoder/decoder:

```java
BuffJsonEncoder encoder = BuffJson.encoder();
BuffJsonDecoder decoder = BuffJson.decoder();

// Register modules — protobuf messages handled by BuffJson, POJOs by fastjson2
JSONFactory.getDefaultObjectWriterProvider().register(encoder.writerModule());
JSONFactory.getDefaultObjectReaderProvider().register(decoder.readerModule());

// Now both work with fastjson2
JSON.toJSONString(myProtoMessage);  // uses BuffJson
JSON.toJSONString(myPojo);          // uses fastjson2 default
```

### Jackson integration

The `buff-json-jackson` module provides a Jackson `Module` for projects that use Jackson as their JSON library. It wraps buff-json's encoder/decoder under Jackson's serialization API, so protobuf messages work seamlessly alongside POJOs and records in `ObjectMapper`:

```java
import io.suboptimal.buffjson.jackson.BuffJsonJacksonModule;

// Register with your ObjectMapper
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new BuffJsonJacksonModule());

// Protobuf messages serialize/deserialize like any other type
String json = mapper.writeValueAsString(myProtoMessage);
MyMessage msg = mapper.readValue(json, MyMessage.class);

// Works in records and POJOs alongside regular fields
record ApiResponse(String status, MyMessage data) {}
String responseJson = mapper.writeValueAsString(new ApiResponse("ok", msg));
```

For optimal deserialization performance, enable `StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` — this lets the deserializer extract raw JSON substrings directly instead of streaming tokens through a buffer:

```java
ObjectMapper mapper = JsonMapper.builder()
    .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
    .addModule(new BuffJsonJacksonModule())
    .build();
```

For `google.protobuf.Any` support, pass configured encoder and decoder:

```java
var registry = TypeRegistry.newBuilder().add(MyMessage.getDescriptor()).build();
mapper.registerModule(new BuffJsonJacksonModule(
    BuffJson.encoder().setTypeRegistry(registry),
    BuffJson.decoder().setTypeRegistry(registry)));
```

### JSON Schema generation

Generate [JSON Schema (draft 2020-12)](https://json-schema.org/draft/2020-12/schema) from protobuf message descriptors. Useful for OpenAPI 3.1+, AsyncAPI 3.0+, and MCP tool definitions.

Provided by the `buff-json-schema` module. When used with the protoc plugin, proto comments are automatically included as `description` fields in the schema.

```java
import io.suboptimal.buffjson.schema.ProtobufSchema;

// From a Descriptor
Map<String, Object> schema = ProtobufSchema.generate(MyMessage.getDescriptor());

// From a Message class
Map<String, Object> schema = ProtobufSchema.generate(MyMessage.class);
```

The schema reflects the [Proto3 JSON mapping](https://protobuf.dev/programming-guides/proto3/#json): int64 types become `{"type": "string", "format": "int64"}`, Timestamp becomes `{"type": "string", "format": "date-time"}`, enums become `{"type": "string", "enum": [...]}`, bytes become `{"type": "string", "contentEncoding": "base64"}`, etc. Messages include `title` from the type name and `description` from proto comments (when the protoc plugin is used). Recursive messages use `$defs`/`$ref`. Returns `Map<String, Object>` for portability — serialize with any JSON library or pass directly to schema-consuming tooling.

#### [buf.validate](https://buf.build/docs/protovalidate/) support

When [`build.buf:protovalidate`](https://buf.build/docs/protovalidate/) is on the classpath, field-level validation constraints are automatically mapped to JSON Schema keywords:

```protobuf
message CreateUserRequest {
    string name = 1 [(buf.validate.field).string.min_len = 1, (buf.validate.field).string.max_len = 100];
    string email = 2 [(buf.validate.field).string.email = true];
    int32 age = 3 [(buf.validate.field).int32 = {gte: 0, lte: 150}];
    repeated string tags = 4 [(buf.validate.field).repeated = {min_items: 1, unique: true}];
}
```

Generates:

```json
{
  "type": "object",
  "title": "CreateUserRequest",
  "properties": {
    "name": {"type": "string", "minLength": 1, "maxLength": 100},
    "email": {"type": "string", "format": "email"},
    "age": {"type": "integer", "minimum": 0, "maximum": 150},
    "tags": {"type": "array", "items": {"type": "string"}, "minItems": 1, "uniqueItems": true}
  }
}
```

Supported mappings include `minLength`/`maxLength`, `pattern`, `format` (email, uri, uuid, hostname, ipv4, ipv6), `minimum`/`maximum`/`exclusiveMinimum`/`exclusiveMaximum`, `minItems`/`maxItems`/`uniqueItems`, `minProperties`/`maxProperties`, `const`, `enum`, and `required`. Constraints without a JSON Schema equivalent (prefix, suffix, contains, CEL expressions) are included as `description` text. The dependency is optional — schema generation works without it.

### Swagger / OpenAPI integration

The `buff-json-swagger` module provides a Swagger `ModelConverter` that automatically resolves protobuf `Message` types to OpenAPI 3.1 schemas using `buff-json-schema`:

```java
import io.suboptimal.buffjson.swagger.ProtobufModelConverter;

ModelConverters.getInstance(true).addConverter(new ProtobufModelConverter());
```

Once registered, any protobuf message type used in your API controllers is automatically converted to a proper OpenAPI schema — nested messages, maps, enums, well-known types, recursive types (`$ref`), and [buf.validate](https://buf.build/docs/protovalidate/) constraints are all handled. Schema definitions use full proto names (e.g. `my.package.MyMessage`) to avoid collisions across packages.

## Proto3 JSON Spec Compliance

|                                                                  Feature                                                                  |  Status   |
|-------------------------------------------------------------------------------------------------------------------------------------------|-----------|
| All scalar types (int32, int64, uint32, uint64, sint32, sint64, fixed32, fixed64, sfixed32, sfixed64, float, double, bool, string, bytes) | Supported |
| Unsigned integer formatting (uint32, uint64)                                                                                              | Supported |
| int64/uint64 as quoted strings                                                                                                            | Supported |
| NaN, Infinity, -Infinity as quoted strings                                                                                                | Supported |
| Nested messages                                                                                                                           | Supported |
| Repeated fields                                                                                                                           | Supported |
| Map fields (all key types: string, int, bool, etc.)                                                                                       | Supported |
| Oneof fields                                                                                                                              | Supported |
| Enums as string names                                                                                                                     | Supported |
| Proto3 default value omission                                                                                                             | Supported |
| Proto3 explicit presence (`optional` keyword)                                                                                             | Supported |
| Custom `json_name`                                                                                                                        | Supported |
| `google.protobuf.Timestamp` (RFC 3339)                                                                                                    | Supported |
| `google.protobuf.Duration`                                                                                                                | Supported |
| `google.protobuf.FieldMask` (camelCase paths)                                                                                             | Supported |
| `google.protobuf.Struct` / `Value` / `ListValue`                                                                                          | Supported |
| All 9 wrapper types (`Int32Value`, `StringValue`, etc.)                                                                                   | Supported |
| `google.protobuf.Any` (with TypeRegistry)                                                                                                 | Supported |
| `google.protobuf.Empty`                                                                                                                   | Supported |
| Deserialization (JSON to protobuf)                                                                                                        | Supported |

## Building

Requires Java 21+ and Maven 3.9+.

```bash
mvn clean install
```

## Running Benchmarks

```bash
# Full suite — builds, runs JMH, generates markdown report
./run-benchmarks.sh

# Specific benchmark subset
./run-benchmarks.sh "ComplexMessage"

# Custom JMH args
./run-benchmarks.sh "SimpleMessage" -wi 3 -i 5 -f 2

# Direct JMH (after mvn package)
java -jar buff-json-benchmarks/target/benchmarks.jar -wi 1 -i 1 -f 1
```

Reports are written to `benchmark-reports/` with raw output, JSON data, and markdown report.

## Running Tests

```bash
mvn test
```

The conformance suite parameterizes every test case over **all three encoding paths** (codegen, typed-accessor, pure reflection) and compares output against `JsonFormat.printer().omittingInsignificantWhitespace().print()`. Reachability tests in `BuffJsonMemoryTest` confirm the encoder doesn't retain `Message` references after a call returns. The Jackson module adds tests covering POJO/record integration, tree model interop, and cross-library roundtrips.

In addition, the `buff-json-conformance` module is a testee for Google's **official protobuf conformance suite** (`conformance_test_runner`), exercising buff-json's proto3 JSON encode/decode against the canonical spec corpus. CI builds the C++ runner from matching protobuf source and runs the proto3 JSON suite once; see [buff-json-conformance/CLAUDE.md](buff-json-conformance/CLAUDE.md) to run it locally.

For allocation-rate regression detection, run:

```bash
./allocation-check.sh           # full check (~1 minute)
./allocation-check.sh --quick   # faster, less stable iterations
```

This runs JMH `-prof gc` on a representative subset and asserts `gc.alloc.rate.norm` (B/op) stays within per-benchmark budgets defined in the script. Wired into CI as a separate `allocation-check` job alongside `mvn verify`.

## Project Structure

```
buff-json/
  buff-json/                # Library: BuffJson.encode()/decode() API + internal serialization
  buff-json-protoc-plugin/  # Optional protoc plugin for generated encoders/decoders
  buff-json-schema/         # JSON Schema generation from protobuf descriptors (no fastjson2 dep)
  buff-json-swagger/        # Swagger/OpenAPI ModelConverter for protobuf message schemas
  buff-json-jackson/        # Jackson module wrapping BuffJson for ObjectMapper integration
  buff-json-tests/          # Conformance tests (both paths) + own .proto definitions
  buff-json-benchmarks/     # JMH benchmarks (comparison) + own .proto definitions
  buff-json-conformance/    # Testee for the official protobuf proto3 JSON conformance suite
```

## Dependencies

|                  Dependency                   | Version |                Purpose                 |           Module(s)           |
|-----------------------------------------------|---------|----------------------------------------|-------------------------------|
| `com.google.protobuf:protobuf-java`           | 4.34.1  | Protobuf runtime (Message, Descriptor) | core, schema, jackson, plugin |
| `com.alibaba.fastjson2:fastjson2`             | 2.0.61  | JSON writing engine                    | core, jackson                 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.18.1  | Jackson ObjectMapper integration       | jackson                       |
| `io.swagger.core.v3:swagger-core-jakarta`     | 2.2.38  | Swagger/OpenAPI ModelConverter         | swagger                       |

## License

Apache License 2.0
