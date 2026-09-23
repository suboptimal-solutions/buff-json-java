# Java 25 encode/decode baseline — 22 September 2026

This is the measured baseline for the runtime-encoding investigation at commit
`f3a0342ff3114b254879d192925b36b93a652d9b`. It covers all 30 cases in
`EncodePathsBenchmark` and all 36 parameter combinations in
`DecodePathsBenchmark`. The complete numeric data, including 99.9% confidence
errors and JMH configuration, is in
[`2026-09-22-java25-encode-decode-baseline.csv`](2026-09-22-java25-encode-decode-baseline.csv).

## Environment and protocol

- OpenJDK 25.0.2+10-69, 64-bit Server VM.
- Linux 6.18.44 x86-64.
- 3 vCPUs reported as Intel Xeon Platinum 8370C at 2.80 GHz; one thread per core.
- JMH 1.37, throughput mode, one benchmark thread, two forks.
- Three 1-second warmups and five 1-second measurements per fork.
- Fixed 256 MiB initial/maximum heap and the JMH GC profiler.
- Benchmark jar SHA-256:
  `580a0f94a9cd2623e996ef189fedb9415a3a3eefab2bd1dfb46a432148386edd`.

The exact command was:

```bash
java -Xms256m -Xmx256m -jar buff-json-benchmarks/target/benchmarks.jar '.*(EncodePathsBenchmark|DecodePathsBenchmark).*' -wi 3 -i 5 -w 1s -r 1s -f 2 -t 1 -prof gc -rf json -rff /tmp/java25-encode-decode-baseline.json
```

These absolute numbers describe this container and commit, not a portable
performance promise. Future experiments should rerun baseline and candidate on
the same idle host/JVM. JMH errors below are 99.9% confidence intervals.

## Encoding baseline

Throughput is operations per second; allocation is normalized bytes per
operation. Runtime means the typed-accessor path.

| Shape/output    |      Codegen ops/s |      Runtime ops/s |   Reflection ops/s | Runtime/codegen | Codegen B/op | Runtime B/op | Reflection B/op |
| --------------- | -----------------: | -----------------: | -----------------: | --------------: | -----------: | -----------: | --------------: |
| simple Utf16    | 5,006,721 ±636,126 | 3,948,436 ±455,148 | 2,083,464 ±186,617 |           78.9% |        295.5 |        295.5 |           340.0 |
| simple Utf8     | 6,813,100 ±717,059 | 4,609,666 ±340,379 | 2,223,685 ±168,284 |           67.7% |        271.5 |        271.5 |           316.0 |
| complex Utf16   |    427,190 ±75,745 |    437,649 ±35,177 |    235,902 ±13,503 |          102.4% |      1,449.9 |      1,345.9 |         1,385.9 |
| complex Utf8    |    489,564 ±39,687 |    436,874 ±31,338 |    264,365 ±21,935 |           89.2% |      1,409.9 |      1,321.9 |         1,329.9 |
| map Utf16       |      68,379 ±5,651 |      47,850 ±7,060 |      32,060 ±1,452 |           70.0% |      4,673.6 |      4,889.6 |         7,682.2 |
| map Utf8        |      66,297 ±9,586 |      56,487 ±4,028 |      31,511 ±3,873 |           85.2% |      4,649.5 |      4,781.6 |         7,658.1 |
| struct Utf16    |    341,111 ±21,663 |    336,504 ±20,517 |    375,296 ±26,180 |           98.6% |        959.1 |        959.1 |           735.7 |
| struct Utf8     |    365,010 ±29,546 |    370,748 ±57,553 |    342,459 ±23,838 |          101.6% |        823.4 |        823.4 |           823.4 |
| timestamp Utf16 | 2,785,798 ±209,376 | 2,219,256 ±141,855 | 1,540,091 ±114,584 |           79.7% |        464.0 |        464.0 |           464.0 |
| timestamp Utf8  | 3,319,643 ±330,101 | 2,632,325 ±356,969 | 1,792,010 ±107,410 |           79.3% |        440.0 |        440.0 |           440.0 |

Observed on this run:

- Typed runtime delivered 67.7–102.4% of codegen throughput across the ten
  encode shape/output pairs. The complex UTF-16 and Struct results have
  overlapping confidence intervals and must not be read as runtime wins.
- The largest measured runtime gap was simple UTF-8 (67.7% of codegen), followed
  by map UTF-16 (70.0%). Use map UTF-16 first to evaluate typed map
  specialization, and use simple UTF-8 to profile accessor dispatch before
  deciding whether a whole-message runtime compiler is justified.
- Allocation is equal between codegen and runtime on simple, Struct, and
  Timestamp, while complex and map differ. Getter dispatch alone cannot reduce
  equal allocation; bytes and output materialization need separate
  optimizations.

## Decoding baseline

“Runtime” uses `generated=false`; “generated” uses `generated=true`.

| Shape/input       |      Runtime ops/s |    Generated ops/s | Generated/runtime | Runtime B/op | Generated B/op |
| ----------------- | -----------------: | -----------------: | ----------------: | -----------: | -------------: |
| simple string     | 2,231,520 ±174,265 | 2,601,997 ±151,595 |            116.6% |        461.9 |          394.1 |
| simple utf8       | 2,196,824 ±188,306 | 2,540,897 ±292,122 |            115.7% |        461.9 |          394.1 |
| scalars string    |    690,085 ±43,986 |    736,740 ±47,225 |            106.8% |        871.9 |          871.9 |
| scalars utf8      |    671,445 ±46,638 |    712,965 ±71,234 |            106.2% |        871.9 |          871.9 |
| complex string    |    194,364 ±19,305 |    243,349 ±15,892 |            125.2% |      4,059.8 |        3,794.5 |
| complex utf8      |    196,814 ±21,668 |    231,232 ±28,628 |            117.5% |      4,067.8 |        3,794.5 |
| repeated string   |      41,864 ±3,079 |      51,524 ±2,597 |            123.1% |     17,688.7 |       15,266.6 |
| repeated utf8     |      39,192 ±2,203 |      48,876 ±4,991 |            124.7% |     17,688.7 |       15,266.3 |
| maps string       |      33,478 ±2,449 |      35,214 ±3,618 |            105.2% |     28,622.2 |       25,029.9 |
| maps utf8         |      32,920 ±3,471 |      36,361 ±1,848 |            110.5% |     28,622.0 |       25,029.6 |
| timestamps string | 1,611,840 ±110,066 | 1,932,108 ±274,384 |            119.9% |        640.0 |          584.0 |
| timestamps utf8   |  1,670,285 ±82,251 | 2,098,530 ±112,293 |            125.6% |        640.0 |          584.0 |
| struct string     |    232,457 ±19,497 |    224,084 ±39,683 |             96.4% |      6,753.0 |        6,712.9 |
| struct utf8       |    228,030 ±15,732 |    221,508 ±16,971 |             97.1% |      6,753.0 |        6,712.9 |
| deep string       |    838,448 ±60,865 |  1,223,081 ±68,731 |            145.9% |        939.9 |          701.3 |
| deep utf8         |   838,074 ±104,924 |  1,187,632 ±79,039 |            141.7% |        939.9 |          701.3 |
| any string        |    297,909 ±31,314 |    296,670 ±14,123 |             99.6% |      1,953.6 |        1,945.6 |
| any utf8          |    287,504 ±31,933 |    288,416 ±11,761 |            100.3% |      1,953.6 |        1,913.6 |

Observed on this run:

- Generated decoding was most beneficial for deep nesting (41.7–45.9% higher
  mean throughput) and repeated-heavy messages (23.1–24.7% higher).
- Struct and Any means were effectively at parity; their confidence intervals
  overlap. Work there is dominated by dynamic/WKT behavior that generated
  ordinary-field setters do not remove.
- Generated decoding reduced allocation materially for complex, repeated, maps,
  timestamps, and deep shapes. Scalars had identical normalized allocation.

## How to use this baseline

Use the CSV as a snapshot and sanity reference. For an optimization decision,
rerun the same command against both revisions on the same machine, alternate
order, and apply the repository performance-report thresholds. Do not compare
absolute values from a different CPU or a busy shared runner. Preserve the raw
JMH JSON for paired experiments so fork samples and confidence intervals remain
available.
