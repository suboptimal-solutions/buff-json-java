# Selected performance port — 2026-09-05

This implements the selected changes from [the branch review](performance-branch-review-2026-09-05.md) on main (`cb106d53960bae666287b7d3ee44af1f1c65d09b`).

## Included

- Concrete Timestamp/Duration/Struct/Value/ListValue getters, retaining descriptor-based DynamicMessage fallback and validation.
- Direct quoted numeric map keys in generated, typed, and reflection paths, including correct unsigned uint32/fixed32/uint64/fixed64 output. Boolean keys use constant strings; long keys use a guarded String fallback under BrowserCompatible/WriteClassName to preserve key spelling.
- One exact-size buffer for large uint64 values, cached map descriptors and typed WKT checks, and presence checks before reflective reads.
- Deprecated fields on every path, with deprecation warnings suppressed in generated codecs. This reuses the independent local deprecated-field fix.
- JSON-escaped UTF-16/UTF-8 name constants and Java literal escaping in both codec generators. Custom Unicode, quotes, backslashes, control characters, and literal backslash-u names compile and round-trip.
- The standalone JMH encoding matrix from stash@0, expanded to simple, complex, map-heavy, Struct, and Timestamp messages across generated/typed/reflection and UTF-16/UTF-8 output.

Context reuse remains a separate API/configuration change. Packed names, primitive-list rewrites, bulk string-list writes, and CodSpeed infrastructure remain deferred.

## Validation

Clean `mvn -B -ntp clean verify` runs build the full Maven reactor on Corretto Java 21.0.11 and 25.0.4, targeting Java 21 with `-Xlint:all,-processing -Werror`. All 496 tests pass, including actual DynamicMessage WKTs and unsigned map entries, deprecated-field fixtures, and custom-name round-trips in both output encodings and decoders.

JsonFormat 4.34.1 itself does not escape arbitrary custom field names when printing. The custom-name regression test uses its valid proto-name output for field values, renames keys with JSON escaping, and validates BuffJson output through JsonFormat's parser.

The official external conformance runner was unavailable (`CONF_TEST_PATH` unset; `conformance_test_runner` absent from PATH). The conformance testee builds successfully; the repository's Maven conformance tests above ran.

## Measurements

| Java |              Benchmark               | Throughput change | B/op, main → port | 99.9% CIs overlap |
|------|--------------------------------------|------------------:|------------------:|-------------------|
| 21   | SimpleMessageBenchmark.compiledUtf16 |             -2.3% |         296 → 296 | yes               |
| 21   | SimpleMessageBenchmark.compiledUtf8  |             -1.2% |         272 → 272 | yes               |
| 21   | WktBenchmark.structRuntime           |           +115.9% |        1369 → 847 | no                |
| 21   | WktBenchmark.timestampRuntime        |            +37.0% |         568 → 464 | no                |
| 21   | RepeatedAndMapBenchmark.mapCompiled  |             +4.9% |       8241 → 5025 | yes               |
| 21   | RepeatedAndMapBenchmark.mapRuntime   |            +18.4% |       6983 → 4805 | yes               |
| 25   | SimpleMessageBenchmark.compiledUtf16 |             -0.5% |         296 → 296 | yes               |
| 25   | SimpleMessageBenchmark.compiledUtf8  |             +4.0% |         272 → 272 | yes               |
| 25   | WktBenchmark.structRuntime           |           +124.7% |        1305 → 736 | no                |
| 25   | WktBenchmark.timestampRuntime        |            +39.6% |         568 → 464 | no                |
| 25   | RepeatedAndMapBenchmark.mapCompiled  |             +7.8% |       6599 → 4590 | yes               |
| 25   | RepeatedAndMapBenchmark.mapRuntime   |            +25.8% |       6983 → 4805 | no                |

Struct and Timestamp gains are clear on both JVMs. Map allocation reduction is consistent; the Java 21 map throughput intervals overlap, as do generated-map intervals on Java 25. Scalar controls show no established throughput change and identical allocations. Overlap is a conservative reading of JMH intervals, not a separate statistical significance test.

The map rows come from the final map comparison, rerun after the writer-feature fixes. Those fixes do not affect the measured scalar or WKT shapes. [Full rates, errors, and allocation summary](performance-results/2026-09-05-comparison.csv).

All 14 allocation budgets pass on Java 21, including new typed Struct/Timestamp UTF-16/UTF-8 checks and generated/typed map checks. The UTF-8 Struct budget was set to 900 B/op after measuring 712 B/op. [Measured results](performance-results/2026-09-05-allocation.csv).

The comparison runs main and the combined port sequentially, with order reversed on Java 25. It uses one thread, two forks, three 1-second warmups, four 1-second measurements, a 256 MiB fixed heap, and JMH's GC profiler. Both jars target Java 21 and use protobuf 4.34.1 and fastjson2 2.0.63. The retained main jar was built with javac 21; the port jar came from the final clean javac 25 build. Compiler version was therefore not held constant in this follow-up; the original branch review also contains same-compiler isolated measurements. These are local microbenchmarks, not application throughput guarantees.

The compact comparison and allocation CSV files are included alongside this report. Full JMH JSON, logs, and the original run scripts remain local under the ignored `benchmark-reports/port-20260905/` directory.

To repeat the measured subset, build main and this branch in separate checkouts and run each jar sequentially with each desired JVM:

```bash
"$JAVA_HOME/bin/java" -jar "$BENCHMARK_JAR" \
  'SimpleMessageBenchmark.(compiledUtf16|compiledUtf8)$|WktBenchmark.(structRuntime|timestampRuntime)$|RepeatedAndMapBenchmark.(mapCompiled|mapRuntime)$' \
  -t 1 -f 2 -wi 3 -i 4 -w 1s -r 1s -prof gc \
  -jvmArgs '-Xms256m -Xmx256m' -foe true -rf json -rff "$RESULTS_FILE"
```

Build with `mvn -B -ntp clean package`, set `BENCHMARK_JAR` to each checkout's `buff-json-benchmarks/target/benchmarks.jar`, and use a distinct `RESULTS_FILE` for each JVM/variant. Run `./allocation-check.sh` from this branch for the 14 allocation guards.
