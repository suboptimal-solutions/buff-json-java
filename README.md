# buff-fastjson-java

Fast JSON serialization for Protocol Buffer messages in Java.

## Goals

- **Performance**: Outperform `JsonFormat.printer().print()` from protobuf-java-util
- **GC-free**: Minimize or eliminate garbage collection pressure during serialization
- **Lock-free**: No synchronization overhead for concurrent usage
- **Simple API**: Single static method entry point

Inspired by [fastjson2](https://github.com/alibaba/fastjson2) and [buffa](https://github.com/anthropics/buffa).

## Usage

```java
import io.suboptimal.buffjson.BuffJSON;

String json = BuffJSON.encode(myProtoMessage);
```

## Building

Requires Java 21+ and Maven 3.9+.

```bash
mvn clean install
```

## Running Benchmarks

```bash
# Full benchmark run
java -jar buff-fastjson-benchmarks/target/benchmarks.jar

# Quick sanity check
java -jar buff-fastjson-benchmarks/target/benchmarks.jar -wi 1 -i 1 -f 1

# Specific benchmark
java -jar buff-fastjson-benchmarks/target/benchmarks.jar SimpleMessageBenchmark
```

## Project Structure

```
buff-fastjson-java/
  buff-fastjson-core/        # Library: BuffJSON.encode() API
  buff-fastjson-benchmarks/   # Proto definitions, JMH benchmarks, tests
```

## License

Apache License 2.0
