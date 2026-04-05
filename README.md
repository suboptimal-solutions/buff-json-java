# Buff JSON (Java)

Blazingly fast ⚡️ JSON serialization for Protocol Buffer messages in Java, compliant with the [Proto3 JSON spec](https://protobuf.dev/programming-guides/proto3/#json).

## Performance

Up to ~10x faster than `JsonFormat.printer().print()` from protobuf-java-util.

The optional protoc plugin generates message-specific encoders that use typed accessors directly (no reflection, no boxing), providing an additional ~2x over the runtime path:

|              Message type               | JsonFormat (ops/s) | BuffJson runtime (ops/s) | BuffJson codegen (ops/s) | Codegen vs JsonFormat |
|-----------------------------------------|--------------------|--------------------------|--------------------------|-----------------------|
| SimpleMessage (6 fields)                | ~1.3M              | ~5M                      | ~12M                     | **~9x**               |
| ComplexMessage (nested, maps, repeated) | ~142K              | ~771K                    | ~1.5M                    | **~10x**              |

Benchmarked on JDK 21 (Corretto) with JMH.

## How it works

Uses [Alibaba fastjson2](https://github.com/alibaba/fastjson2) as the JSON writing engine via its `ObjectWriterModule` extension point. We register a custom module that handles `com.google.protobuf.Message` types, extracting fields via protobuf Descriptors and delegating all JSON formatting (buffering, number encoding, string escaping) to fastjson2's optimized infrastructure.

**Runtime path** (works with any message, no build changes):
- **No `getAllFields()` / TreeMap allocation** per call (unlike `JsonFormat`)
- **No Gson dependency** for string escaping (unlike `JsonFormat`)
- **Cached `MessageSchema`** per message Descriptor (one-time cost)
- **Pre-computed field name chars** for `writeNameRaw()` — avoids per-field string encoding
- **fastjson2 ThreadLocal buffer reuse** eliminates per-call allocations

**Codegen path** (optional protoc plugin, ~2-3x additional speedup):
- **Direct typed accessors** — `message.getId()` returns `int`, no boxing
- **No runtime type dispatch** — each field's encoding logic is inlined at compile time
- **No schema cache lookup** — field iteration order and names are hardcoded
- **Direct nested encoder calls** — nested messages call `INSTANCE.writeFields()` directly, bypassing the runtime registry
- **Inline WKT Timestamp/Duration** — typed accessor calls (`ts.getSeconds()`, `ts.getNanos()`) bypass descriptor lookup and reflection
- **Pre-cached enum names** — static `String[]` array lookup, no `forNumber()` or descriptor calls

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

Also add the generated ServiceLoader file as a resource:

```xml
<resources>
    <resource>
        <directory>${project.build.directory}/generated-sources/protobuf</directory>
        <includes>
            <include>META-INF/**</include>
        </includes>
    </resource>
</resources>
```

No code changes needed — generated encoders and decoders are discovered automatically via `ServiceLoader`. If the plugin is not configured, the runtime reflection path is used.

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

For `google.protobuf.Any` support, pass a `TypeRegistry`:

```java
mapper.registerModule(new BuffJsonJacksonModule(
    TypeRegistry.newBuilder().add(MyMessage.getDescriptor()).build()));
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

#### buf.validate support

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

232 conformance tests (core module) compare `BuffJson.encode()` output against `JsonFormat.printer().omittingInsignificantWhitespace().print()` for all supported proto3 JSON features. The Jackson module adds 38 additional tests covering conformance, POJO/record integration, tree model interop, and cross-library roundtrips.

## Project Structure

```
buff-json/
  buff-json/                # Library: BuffJson.encode()/decode() API + internal serialization
  buff-json-protoc-plugin/  # Optional protoc plugin for generated encoders/decoders
  buff-json-schema/         # JSON Schema generation from protobuf descriptors (no fastjson2 dep)
  buff-json-jackson/        # Jackson module wrapping BuffJson for ObjectMapper integration
  buff-json-tests/          # Conformance tests (both paths) + own .proto definitions
  buff-json-benchmarks/     # JMH benchmarks (comparison) + own .proto definitions
```

## Dependencies

|                  Dependency                   | Version |                Purpose                 |           Module(s)           |
|-----------------------------------------------|---------|----------------------------------------|-------------------------------|
| `com.google.protobuf:protobuf-java`           | 4.34.1  | Protobuf runtime (Message, Descriptor) | core, schema, jackson, plugin |
| `com.alibaba.fastjson2:fastjson2`             | 2.0.61  | JSON writing engine                    | core, jackson                 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.18.1  | Jackson ObjectMapper integration       | jackson                       |

## License

Apache License 2.0
