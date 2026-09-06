# CLAUDE.md - buff-json-benchmarks

## Module Purpose

JMH benchmarks comparing `BuffJson.encode()` against `JsonFormat.printer().print()`.
Owns its own .proto definitions for benchmark messages.

## Benchmarks — Comparison Matrix

`SimpleMessageBenchmark` and `DoubleHeavyBenchmark` are split into UTF-16 (`encode` → String) and UTF-8 (`encodeToBytes` → byte[]) variants per encoder, since the UTF-8 byte[] field-name pre-encoding (Tier 5) and `JSONWriterUTF8` differ enough to be worth measuring independently:

- `compiledUtf16()` / `compiledUtf8()` — codegen path
- `runtimeUtf16()` / `runtimeUtf8()` — typed-accessor runtime (`setGeneratedEncoders(false)`)
- `fastjson2PojoUtf16()` / `fastjson2PojoUtf8()` — fastjson2 POJO baseline (where applicable)
- `protoJsonFormat()` — `JsonFormat.printer().print()` baseline (UTF-16 only)

Older benchmarks (`ComplexMessageBenchmark`, `WktBenchmark`, etc.) still use `buffJsonCompiled` / `buffJsonRuntime` / `protoJsonFormat` naming. Random variants use a 1024-element message pool (`index++ & MASK` cycling).

## Benchmark Classes

`EncodePathsBenchmark` is an ordinary JMH matrix ported from the performance stash: simple, complex, map-heavy, Struct, and Timestamp shapes × codegen/typed/reflection × UTF-16/UTF-8. It has no CodSpeed dependency.

|              Class              |                             Message                             |                    Focus                    |
|---------------------------------|-----------------------------------------------------------------|---------------------------------------------|
| `SimpleMessageBenchmark`        | 6-field flat message (string, int32, int64, double, bool, enum) | Scalar baseline (UTF-16/UTF-8 split)        |
| `DoubleHeavyBenchmark`          | 25-double IoT/telemetry profile + POJO baseline                 | Number-formatting cost (UTF-16/UTF-8 split) |
| `ComplexMessageBenchmark`       | Nested, maps, repeated, oneof, bytes, timestamps, enum          | Full-featured message                       |
| `AllScalarsBenchmark`           | All 15 proto3 scalar types + enum                               | Type coverage                               |
| `WktBenchmark`                  | Timestamp, Duration, Wrappers, Struct (4 sub-scenarios)         | Well-known types                            |
| `AnyBenchmark`                  | Any containing scalars message + Any containing Timestamp       | Type resolution                             |
| `RepeatedAndMapBenchmark`       | 100+ repeated elements, 50+ map entries                         | Collection stress                           |
| `DeepNestingAndStringBenchmark` | 5-level recursive nesting + unicode/escape strings              | Nesting + string stress                     |
| `SimpleMessagePojoBenchmark`    | Plain POJO equivalent via fastjson2 (generic + @JSONCompiled)   | Ceiling comparison                          |
| `ComplexMessagePojoBenchmark`   | Plain POJO equivalent via fastjson2 (generic + @JSONCompiled)   | Ceiling comparison                          |

## Running Benchmarks

```bash
# Full suite with report generation
./run-benchmarks.sh

# Specific benchmark subset
./run-benchmarks.sh "ComplexMessage"

# Custom JMH args
./run-benchmarks.sh "ComplexMessage" -wi 3 -i 5 -f 2
```

`run-benchmarks.sh` rebuilds the project (`mvn package -DskipTests`), runs JMH, and generates:
- `benchmark-reports/<timestamp>-raw.txt` — full JMH console output
- `benchmark-reports/<timestamp>-results.json` — machine-readable JSON
- `benchmark-reports/<timestamp>-report.md` — markdown report with codegen/runtime/JsonFormat comparison table

## CI Performance Comparison

The `Performance` workflow compares exact base/head revisions on Java 21 and 25, using identical candidate benchmark sources and a shared runner per JVM. `.github/performance/compare.py` runs the 30-case `EncodePathsBenchmark` matrix; `report.py` validates data and renders advisory throughput/allocation changes. A separate trusted `workflow_run` publisher updates PR comments and commit checks. See [performance CI documentation](../docs/performance-ci.md) for baselines, protocol, reporting, rollout, and local smoke runs.

## Allocation Regression Check

`./allocation-check.sh` (at repo root) runs JMH `-prof gc` on a representative subset of benchmarks (SimpleMessage codegen+runtime × UTF-16+UTF-8, ComplexMessage codegen+runtime, DoubleHeavy codegen × UTF-16+UTF-8, typed Struct/Timestamp × UTF-16+UTF-8, and map-heavy codegen+runtime) and asserts `gc.alloc.rate.norm` (B/op) stays within per-benchmark budgets. Total runtime ~2 minutes; `--quick` flag for local iteration. Wired into CI as a separate `allocation-check` job. Catches missed zero-alloc paths and new String/byte[] allocations on the hot path.

## Proto Files

- `simple_message.proto` — SimpleMessage + Status enum
- `complex_message.proto` — Address, Tag, ComplexMessage (nested, maps, repeated, oneof, bytes, timestamps)
- `benchmark_stress.proto` — BenchAllScalars, BenchRepeatedHeavy, BenchMapHeavy, BenchDeepNesting, BenchDoubleHeavy (25 doubles), BenchStringHeavy, BenchAny
- `benchmark_wkt.proto` — BenchTimestamps, BenchDurations, BenchWrappers, BenchStruct

## POJO Baselines

`SimpleMessagePojoBenchmark` and `ComplexMessagePojoBenchmark` serialize plain Java POJOs with fastjson2 to establish the ceiling for serialization speed. The POJO has pre-formatted data (timestamps as strings, base64 payload as string, enum as string), so the gap between codegen and POJO represents inherent protobuf overhead.

## Build Gotchas

- **JMH annotation processor**: Must be in `<annotationProcessorPaths>` of maven-compiler-plugin,
  not just as a dependency. Otherwise `META-INF/BenchmarkList` won't be generated.
- **maven-shade-plugin**: Needs `AppendingTransformer` for `META-INF/BenchmarkList` and
  `META-INF/CompilerHints`. Also needs `ServicesResourceTransformer` for JMH's ServiceLoader.
- **dependency-reduced-pom.xml**: Generated by shade plugin, gitignored. Delete it if builds behave strangely.

