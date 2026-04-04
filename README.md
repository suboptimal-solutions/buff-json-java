# buff-fastjson-java

Fast JSON serialization for Protocol Buffer messages in Java, compliant with the [Proto3 JSON spec](https://protobuf.dev/programming-guides/proto3/#json).

## Performance

Up to ~10x faster than `JsonFormat.printer().print()` from protobuf-java-util.

The optional protoc plugin generates message-specific encoders that use typed accessors directly (no reflection, no boxing), providing an additional ~2x over the generic path:

|              Message type               | JsonFormat (ops/s) | BuffJSON generic (ops/s) | BuffJSON codegen (ops/s) | Codegen vs JsonFormat |
|-----------------------------------------|--------------------|--------------------------|--------------------------|-----------------------|
| SimpleMessage (6 fields)                | ~1.3M              | ~5M                      | ~12M                     | **~9x**               |
| ComplexMessage (nested, maps, repeated) | ~142K              | ~771K                    | ~1.5M                    | **~10x**              |

Benchmarked on JDK 21 (Corretto) with JMH.

## How it works

Uses [Alibaba fastjson2](https://github.com/alibaba/fastjson2) as the JSON writing engine via its `ObjectWriterModule` extension point. We register a custom module that handles `com.google.protobuf.Message` types, extracting fields via protobuf Descriptors and delegating all JSON formatting (buffering, number encoding, string escaping) to fastjson2's optimized infrastructure.

**Generic path** (works with any message, no build changes):
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

### Basic (generic path, no build changes)

```java
import io.suboptimal.buffjson.BuffJSON;

String json = BuffJSON.encode(myProtoMessage);
```

### With Any type support

```java
Encoder encoder = BuffJSON.encoder()
    .withTypeRegistry(TypeRegistry.newBuilder()
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
                <artifactId>buff-fastjson-protoc-plugin</artifactId>
                <version>${buff-fastjson.version}</version>
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

No code changes needed — generated encoders and decoders are discovered automatically via `ServiceLoader`. The API (`BuffJSON.encode()` / `BuffJSON.decode()`) is unchanged. If the plugin is not configured, the generic reflection path is used.

The serialization output matches `JsonFormat.printer().omittingInsignificantWhitespace().print()` exactly.

### Deserialization (JSON to protobuf)

```java
// Basic
MyMessage msg = BuffJSON.decode(json, MyMessage.class);

// With Any type support
Decoder decoder = BuffJSON.decoder()
    .withTypeRegistry(TypeRegistry.newBuilder()
        .add(MyMessage.getDescriptor())
        .build());
MyMessage msg = decoder.decode(json, MyMessage.class);
```

### JSON Schema generation

Generate [JSON Schema (draft 2020-12)](https://json-schema.org/draft/2020-12/schema) from protobuf message descriptors. Useful for OpenAPI 3.1+, AsyncAPI 3.0+, and MCP tool definitions.

Provided by the `buff-protobuf-schema` module. When used with the protoc plugin, proto comments are automatically included as `description` fields in the schema.

```java
import io.suboptimal.buffjson.schema.ProtobufSchema;

// From a Descriptor
Map<String, Object> schema = ProtobufSchema.generate(MyMessage.getDescriptor());

// From a Message class
Map<String, Object> schema = ProtobufSchema.generate(MyMessage.class);
```

The schema reflects the [Proto3 JSON mapping](https://protobuf.dev/programming-guides/proto3/#json): int64 types become `{"type": "string", "format": "int64"}`, Timestamp becomes `{"type": "string", "format": "date-time"}`, enums become `{"type": "string", "enum": [...]}`, bytes become `{"type": "string", "contentEncoding": "base64"}`, etc. Messages include `title` from the type name and `description` from proto comments (when the protoc plugin is used). Recursive messages use `$defs`/`$ref`. Returns `Map<String, Object>` for portability — serialize with any JSON library or pass directly to schema-consuming tooling.

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
java -jar buff-fastjson-benchmarks/target/benchmarks.jar -wi 1 -i 1 -f 1
```

Reports are written to `benchmark-reports/` with raw output, JSON data, and markdown report.

## Running Tests

```bash
mvn test
```

84 conformance tests compare `BuffJSON.encode()` output against `JsonFormat.printer().omittingInsignificantWhitespace().print()` for all supported proto3 JSON features.

## Project Structure

```
buff-fastjson-java/
  buff-fastjson-core/           # Library: BuffJSON.encode()/decode() API + internal serialization
  buff-fastjson-protoc-plugin/  # Optional protoc plugin for generated encoders/decoders
  buff-protobuf-schema/         # JSON Schema generation from protobuf descriptors (no fastjson2 dep)
  buff-fastjson-tests/          # Conformance tests (both paths) + own .proto definitions
  buff-fastjson-benchmarks/     # JMH benchmarks (3-way comparison) + own .proto definitions
```

## Dependencies

|             Dependency              | Version |                Purpose                 |      Module(s)       |
|-------------------------------------|---------|----------------------------------------|----------------------|
| `com.google.protobuf:protobuf-java` | 4.34.1  | Protobuf runtime (Message, Descriptor) | core, schema, plugin |
| `com.alibaba.fastjson2:fastjson2`   | 2.0.61  | JSON writing engine                    | core                 |

## License

Apache License 2.0
