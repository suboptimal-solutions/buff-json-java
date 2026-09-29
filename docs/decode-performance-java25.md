Decode performance on Java 25, 28–29 September 2026
===================================================

This document records an investigation into what limits protobuf-JSON *decode* speed on current runtimes, which changes were made, what each one gained and what was measured but not worth doing. Everything here is on branch `claude/upbeat-brahmagupta-jvxydg`.

**Setup.** Linux x86-64 VM (4 vCPU), Corretto 25.0.4 (OpenJDK 21.0.10 for the comparison), fastjson2 2.0.63, protobuf-java 4.34.1, `-Xms256m -Xmx256m`. JMH throughput, 2 forks × (3 warm-up + 4 measured) × 1 s, `-prof gc` for allocation. Benchmarks decode 1,024 seeded messages per shape from `String` (Latin-1 coded, so a byte view is available) and from `byte[]`; every fixture is verified against the original message during set-up. The VM is shared, and run-to-run noise on a single case is ±5–10%, so single-case differences below about 10% are not conclusions; the geometric means over 18–20 cases are steadier (±3%).

## Summary

Decoding a protobuf message from JSON was dominated by generic parsing work that the message type makes unnecessary: looking member names up as `String`s, converting numbers through fastjson2's lenient readers, and allocating a `String` for every token that is only compared. Two changes remove most of it, and both are exact: anything they do not recognise with certainty is handled by the code that was there before.

|                                            Change                                             |                Where                |                                  Effect on JDK 25                                  |                                    Proto3 JSON impact                                    |
|-----------------------------------------------------------------------------------------------|-------------------------------------|------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| Exact member-name and enum-name dispatch                                                      | generated decoders, runtime decoder | **+20%** generated (geometric mean of 18 cases, up to +55%), **+12%** runtime path | none: anything but the exact spelling takes the old lookup                               |
| Canonical-input fast path (`readFast`), with word-at-a-time integers and Eisel-Lemire doubles | generated decoders                  | **+25%** on top of the above (20 cases, up to +82%, none below −1%)                | none by construction: anything not provably canonical is re-read by the previous decoder |
| Endless loop on non-object array elements (denial of service)                                 | all decoders                        | fixes a hang: `{"items":[1]}` allocated until `OutOfMemoryError`                   | stricter: now a `JSONException`                                                          |
| `null` in lists / maps of `Value` and `NullValue`                                             | all decoders                        | correctness                                                                        | **closer to the spec**: was dropped, now `NULL_VALUE` as `JsonFormat` yields             |

Cumulatively the generated decoder is **+49%** faster than before this work on JDK 25 (geometric mean of the 18 benchmark cases; **+66%** over the 14 cases the fast path handles; up to +130% for small scalar-heavy messages), and **+45%** on JDK 21:

**Generated decoder, JDK 25: before this work vs now**

|   shape    | input  | before ops/s | after ops/s | change | B/op before | B/op after |
|------------|--------|-------------:|------------:|-------:|------------:|-----------:|
| simple     | string |        4.71M |      10.82M |  +130% |         390 |        189 |
| simple     | utf8   |        4.75M |      10.20M |  +115% |         390 |        189 |
| scalars    | string |        1.15M |       2.07M |   +79% |         863 |        354 |
| scalars    | utf8   |        1.09M |       1.99M |   +82% |         863 |        354 |
| timestamps | string |        4.08M |       7.80M |   +91% |         584 |        208 |
| timestamps | utf8   |        3.85M |       7.78M |  +102% |         584 |        208 |
| deep       | string |        2.21M |       3.94M |   +78% |         701 |        525 |
| deep       | utf8   |        2.23M |       3.76M |   +68% |         701 |        525 |
| maps       | string |        62.7k |       79.6k |   +27% |       24981 |      21847 |
| maps       | utf8   |        60.4k |       82.7k |   +37% |       24981 |      21847 |
| complex    | string |       411.2k |      624.1k |   +52% |        3795 |       3255 |
| complex    | utf8   |       394.3k |      634.0k |   +61% |        3795 |       3255 |
| repeated   | string |        84.9k |      106.8k |   +26% |       15266 |      14466 |
| repeated   | utf8   |        87.0k |      106.2k |   +22% |       15266 |      14466 |
| struct     | string |       411.3k |      413.1k |    +0% |        6713 |       6713 |
| struct     | utf8   |       389.3k |      381.2k |    -2% |        6713 |       6713 |
| any        | string |       485.1k |      495.3k |    +2% |        1946 |       1946 |
| any        | utf8   |       474.1k |      509.2k |    +7% |        1946 |       1946 |

geometric mean: **+49.0%** over 18 cases

What was measured and **not** worth doing:

- `-XX:+UseCompactObjectHeaders` (JEP 519, product in 25) and `-XX:+UseParallelGC` are within ±3% on every decode shape. The JVM does not need tuning for this.
- fastjson2 2.0.65 as a drop-in for 2.0.63 changes the previous generated decoder by -0.9% geometric mean over 18 cases (range -8% to +6%), i.e. noise.
- A generic cursor object driven token by token (the obvious "own tokenizer" design) is *slower* than fastjson2 on most shapes (−3% geometric mean); the win comes from keeping the position in a local variable of generated straight-line code (section 2.3).
- Java 25 features that do not change steady-state decode throughput: AOT class loading/linking (JEP 483/514) and AOT profiling (JEP 515) shorten start-up and warm-up (not measured here); the Vector API is still incubating in 25 and needs `--add-modules`, which a library cannot require. The one Java-25-era feature that could matter is the final Class-File API (section 5).

## 1. Where the time went

Async-profiler (CPU, `-XX:+DebugNonSafepoints`) on the benchmark shapes before any change:

- **Member names**: `JSONReaderASCII.readFieldName` was 15% (runtime path) to **27%** (map-heavy generated) of the samples. It scans, packs up to 16 bytes, probes a global name cache (which map keys thrash), builds a `String`, and the caller then does a `String` switch or `HashMap.get` (`String.equals` 7%, `HashMap.getNode` 5%, an itable stub for the per-field lambda 9% on the runtime path).
- **Numbers**: reading an int32 strictly went through `readDoubleValue` (27% on the repeated shape, plus `IOUtils.digit2` 9%); 16–17 digit doubles are handled by fastjson2 itself but cost a `String` and `Double.parseDouble` in any parser of our own.
- **Strings**: `readString` + `Arrays.copyOfRange` ≈ 10% everywhere.
- **Protobuf itself**: message and builder construction (`Builder.buildPartial0`, constructors, map puts) is 25–65% of what remains after the changes below. It is not reducible from this side.

A hand-written decoder for one flat message (`DecodeCeilingBenchmark`) reached 18M ops/s against 8M for the generated decoder at the time: a 2× ceiling for parse-side work.

## 2. What was done

### 2.1 Exact name dispatch (`a7d04f2`)

Generated `readMessage` switches on the first four bytes of `"name":` (`JSONReader.getRawInt`) and confirms the rest with fastjson2's `nextIfName4MatchN`, which consumes nothing unless the whole `"name":` matched; everything else (escapes, whitespace before the colon, `snake_case` proto names, unknown members) takes the old `readFieldName()` route, so the fast route can never change which field a name selects. Enums are matched by a `String` switch generated per enum. The runtime path uses the same matcher through an open-addressing table keyed by the 4-byte prefix. The argument layout of `nextIfName4MatchN` lives in one class (`FieldNameMatcher`) that the plugin and the runtime share, and it is self-checked against the fastjson2 on the classpath at start-up (no matcher, no fast route, on a fastjson2 that lacks the methods).

**Runtime (typed builder) decoder, JDK 25: before vs now**

|   shape    | input  | before ops/s | after ops/s | change | B/op before | B/op after |
|------------|--------|-------------:|------------:|-------:|------------:|-----------:|
| simple     | string |        4.18M |       4.56M |    +9% |         462 |        431 |
| simple     | utf8   |        4.23M |       4.45M |    +5% |         462 |        431 |
| scalars    | string |        1.03M |       1.26M |   +22% |         872 |        784 |
| scalars    | utf8   |        1.10M |       1.25M |   +13% |         872 |        784 |
| timestamps | string |        3.14M |       3.79M |   +21% |         640 |        640 |
| timestamps | utf8   |        3.19M |       3.54M |   +11% |         640 |        640 |
| deep       | string |        1.59M |       2.18M |   +37% |         940 |        940 |
| deep       | utf8   |        1.60M |       2.06M |   +29% |         940 |        940 |
| maps       | string |        56.9k |       60.0k |    +6% |       28623 |      28622 |
| maps       | utf8   |        55.5k |       57.6k |    +4% |       28622 |      28622 |
| complex    | string |       321.5k |      422.7k |   +31% |        4060 |       3994 |
| complex    | utf8   |       328.1k |      420.2k |   +28% |        4060 |       3994 |
| repeated   | string |        77.6k |       78.7k |    +1% |       17689 |      17689 |
| repeated   | utf8   |        72.6k |       75.6k |    +4% |       17689 |      17688 |
| struct     | string |       394.8k |      393.5k |    -0% |        6753 |       6753 |
| struct     | utf8   |       399.4k |      400.8k |    +0% |        6753 |       6753 |
| any        | string |       512.9k |      503.1k |    -2% |        1986 |       1986 |
| any        | utf8   |       492.2k |      540.0k |   +10% |        1986 |       1986 |

geometric mean: **+12.2%** over 18 cases

### 2.2 Two defects found while testing (`5f7db28`, `f01aca3`)

- Every message reader treated the opening `{` as optional, so a repeated message field with a non-object element (`{"items":[1]}`, `[x]`, `[,]`, a truncated `[`) produced an empty message *without consuming input*, and the array loop appended empty messages until `OutOfMemoryError` — reachable with about a dozen bytes on all three decode paths. Now a `JSONException` (`FieldReader.objectStart`).
- JSON `null` is a *value* for `google.protobuf.Value` and `NullValue`, also as a list element or a map value. The runtime paths dropped null list elements, generated decoders dropped null map entries, and the runtime turned a null `Value` map value into a `Value` with no kind. `JsonFormat` yields `NULL_VALUE` in all of these; so does every path now (`BuffJsonNullElementsTest`).

### 2.3 Canonical-input fast path

`BuffJsonDecoder.decode` first offers a `byte[]` (or a Latin-1 `String`'s backing array, read in place) to a generated `readFast`. It accepts only input whose meaning is unambiguous — compact or pretty layout, camelCase or proto names, canonical numbers, plain or escaped strings, canonical UTC timestamps and durations — and throws a stackless `Bail` for everything else: a non-canonical spelling, a value it does not read (`Any`, `Struct`, `Value`, `ListValue`, `FieldMask`), malformed input, nesting deeper than 100. The caller then decodes the **whole document again** with the previous decoder, so its results, exception types and messages are untouched. `setFastPath(false)` turns it off.

Design points that mattered for speed:

- **The position is a local variable.** Generated code is straight-line: a local `int p`, a local `byte[] b`, inline comparisons and tiny static helpers that take a position and return the next one; one small static method per field. A `FastInput` cursor object driven token by token (the first implementation) was 16% *slower* than fastjson2 on a string/int recursive message (2.8M vs 3.3M ops/s) although it allocated less; the same logic with the position in a register is 26% *faster* (4.1M ops/s). Every token method of the cursor reloaded and stored the position through memory, and the dependency chain through those stores dominated.
- **Word-at-a-time scanning.** A plain string is located with one 8-byte SWAR pass that finds the first quote, backslash, control or non-ASCII byte; integers of up to 8 digits are recognised and converted with three multiplications and no data-dependent branch.
- **Doubles.** The canonical form of a double is what `Double.toString` prints: 16–17 significant digits, outside the range where one exact IEEE operation is correct. Those numbers went through `Double.parseDouble` (a `String` and about 150 ns); with the generic cursor that alone made the `simple` shape 8% slower than the old decoder. `FastDouble` implements Eisel-Lemire with a 128-bit table of powers of five (kept as text, decoded on first use) and is bit-identical to `Double.parseDouble`.
- **Enums** are matched against the value names by length and bytes without creating a `String`.
- **Wide messages.** No generated method approaches the JIT's 8,000-byte huge-method limit: the `TestAllTypesProto3` decoder (about 300 members) has a 2.8 KB `readFast`, and its name matcher is split into 8 methods by a hash of the prefix.

**Fast path off vs on, JDK 25, compact JSON**

|   shape    | input  | general ops/s | fast path ops/s | change | B/op general | B/op fast |
|------------|--------|--------------:|----------------:|-------:|-------------:|----------:|
| simple     | string |         6.32M |          10.79M |   +71% |          367 |       189 |
| simple     | utf8   |         6.01M |           9.68M |   +61% |          367 |       189 |
| scalars    | string |         1.51M |           2.06M |   +36% |          784 |       354 |
| scalars    | utf8   |         1.41M |           2.05M |   +45% |          784 |       354 |
| timestamps | string |         4.21M |           7.66M |   +82% |          584 |       208 |
| timestamps | utf8   |         4.53M |           7.53M |   +66% |          584 |       208 |
| deep       | string |         3.22M |           3.80M |   +18% |          701 |       525 |
| deep       | utf8   |         3.05M |           3.72M |   +22% |          701 |       525 |
| maps       | string |         62.6k |           85.6k |   +37% |        24981 |     21847 |
| maps       | utf8   |         65.4k |           80.6k |   +23% |        24981 |     21846 |
| complex    | string |        555.8k |          618.7k |   +11% |         3729 |      3255 |
| complex    | utf8   |        536.5k |          590.5k |   +10% |         3729 |      3255 |
| repeated   | string |         90.6k |          101.8k |   +12% |        15266 |     14466 |
| repeated   | utf8   |         88.1k |          103.5k |   +18% |        15242 |     14466 |
| strings    | string |         78.5k |           78.0k |    -1% |        43280 |     43223 |
| strings    | utf8   |        114.1k |          135.2k |   +18% |        16505 |      9869 |
| struct     | string |        376.8k |          406.6k |    +8% |         6713 |      6713 |
| struct     | utf8   |        367.6k |          379.5k |    +3% |         6713 |      6713 |
| any        | string |        502.4k |          500.7k |    -0% |         1946 |      1946 |
| any        | utf8   |        500.4k |          503.6k |    +1% |         1946 |      1946 |

geometric mean: **+24.9%** over 20 cases

Compact output is the best case (`JsonFormat`, most Java producers). Producers that put a blank after every colon and comma (Go `protojson` in some builds) or pretty-print keep most of the gain (a shortcut for the single blank in the whitespace skip lifted the `spaced` layout from +18% to +25% geometric mean):

**Fast path off vs on, JDK 25, spaced JSON**

|  shape   | input  | general ops/s | fast path ops/s | change | B/op general | B/op fast |
|----------|--------|--------------:|----------------:|-------:|-------------:|----------:|
| simple   | string |         5.94M |          10.77M |   +81% |          367 |       189 |
| simple   | utf8   |         6.11M |          10.17M |   +66% |          367 |       189 |
| deep     | string |         3.13M |           3.53M |   +13% |          701 |       525 |
| deep     | utf8   |         3.10M |           3.64M |   +17% |          701 |       525 |
| maps     | string |         64.4k |           82.5k |   +28% |        24981 |     21847 |
| maps     | utf8   |         65.1k |           81.6k |   +25% |        24981 |     21846 |
| complex  | string |        546.8k |          580.1k |    +6% |         3729 |      3255 |
| complex  | utf8   |        529.2k |          572.6k |    +8% |         3749 |      3255 |
| repeated | string |         93.4k |          105.8k |   +13% |        15266 |     14466 |
| repeated | utf8   |         90.5k |           99.4k |   +10% |        15242 |     14466 |

geometric mean: **+24.8%** over 10 cases

**Fast path off vs on, JDK 25, pretty JSON**

|  shape   | input  | general ops/s | fast path ops/s | change | B/op general | B/op fast |
|----------|--------|--------------:|----------------:|-------:|-------------:|----------:|
| simple   | string |         6.12M |           9.62M |   +57% |          367 |       189 |
| simple   | utf8   |         5.84M |           9.50M |   +63% |          367 |       189 |
| deep     | string |         2.30M |           2.59M |   +13% |          701 |       525 |
| deep     | utf8   |         2.38M |           2.64M |   +11% |          701 |       525 |
| maps     | string |         61.8k |           77.9k |   +26% |        24981 |     21847 |
| maps     | utf8   |         60.6k |           77.7k |   +28% |        24981 |     21846 |
| complex  | string |        501.2k |          534.0k |    +7% |         3729 |      3255 |
| complex  | utf8   |        486.3k |          566.6k |   +17% |         3749 |      3255 |
| repeated | string |         88.3k |           99.3k |   +12% |        15266 |     14466 |
| repeated | utf8   |         89.9k |          103.5k |   +15% |        15266 |     14466 |

geometric mean: **+23.6%** over 10 cases

The bail penalty is small: the `struct` and `any` shapes contain an unsupported member in every document, so each decode scans up to it and then decodes again, and they still land within ±8% of the old decoder. `String` inputs that are not Latin-1 coded (any character above U+00FF, the `strings` shape) skip the fast path and are unchanged.

**Mixed workload.** The benchmarks above show the JIT one message class at every call site. Rotating six message types (simple, scalars, timestamps, deep, complex, maps) through one decoder, so that `readFast` is reached through a megamorphic call as in a service that handles several types, gives **+21%** for `byte[]` and **+28%** for `String` input (`DecodeMixedBenchmark`), so the gains do not depend on a favourable profile.

**JDK 21 vs 25.** The same code on JDK 21:

**Fast path off vs on, JDK 21, compact JSON**

|   shape    | input  | general ops/s | fast path ops/s | change | B/op general | B/op fast |
|------------|--------|--------------:|----------------:|-------:|-------------:|----------:|
| simple     | string |         5.95M |          11.62M |   +95% |          367 |       189 |
| simple     | utf8   |         5.72M |          10.72M |   +88% |          367 |       189 |
| scalars    | string |         1.48M |           2.00M |   +35% |          784 |       354 |
| scalars    | utf8   |         1.33M |           2.09M |   +57% |          784 |       354 |
| timestamps | string |         4.70M |           7.25M |   +54% |          584 |       208 |
| timestamps | utf8   |         4.83M |           6.63M |   +37% |          584 |       208 |
| deep       | string |         3.16M |           3.44M |    +9% |          701 |       525 |
| deep       | utf8   |         3.13M |           3.49M |   +12% |          701 |       525 |
| maps       | string |         64.2k |           80.5k |   +25% |        24981 |     21847 |
| maps       | utf8   |         66.5k |           82.6k |   +24% |        24981 |     21847 |
| complex    | string |        496.6k |          546.6k |   +10% |         3729 |      3295 |
| complex    | utf8   |        510.6k |          547.8k |    +7% |         3729 |      3295 |
| repeated   | string |         95.8k |           99.4k |    +4% |        15266 |     14466 |
| repeated   | utf8   |         92.9k |          105.3k |   +13% |        15266 |     14466 |
| strings    | string |         79.4k |           80.9k |    +2% |        43224 |     43223 |
| strings    | utf8   |        121.2k |          128.9k |    +6% |        16505 |      9869 |
| struct     | string |        370.0k |          362.7k |    -2% |         7692 |      7633 |
| struct     | utf8   |        345.3k |          351.0k |    +2% |         7692 |      7633 |
| any        | string |        493.1k |          476.1k |    -3% |         1946 |      1946 |
| any        | utf8   |        505.6k |          482.0k |    -5% |         1946 |      1946 |

geometric mean: **+20.7%** over 20 cases

And the whole way, generated decoder on JDK 21:

**Generated decoder, JDK 21: before this work vs now**

|   shape    | input  | before ops/s | after ops/s | change | B/op before | B/op after |
|------------|--------|-------------:|------------:|-------:|------------:|-----------:|
| simple     | string |        4.62M |      11.28M |  +144% |         398 |        189 |
| simple     | utf8   |        4.35M |      10.88M |  +150% |         398 |        189 |
| scalars    | string |        1.21M |       2.17M |   +79% |         863 |        354 |
| scalars    | utf8   |        1.10M |       2.05M |   +87% |         863 |        354 |
| timestamps | string |        4.05M |       6.98M |   +73% |         584 |        208 |
| timestamps | utf8   |        4.05M |       6.65M |   +64% |         584 |        208 |
| deep       | string |        2.19M |       3.53M |   +61% |         701 |        525 |
| deep       | utf8   |        2.35M |       3.60M |   +53% |         701 |        525 |
| maps       | string |        58.9k |       79.1k |   +34% |       24981 |      21847 |
| maps       | utf8   |        56.5k |       79.9k |   +41% |       25005 |      21847 |
| complex    | string |       354.9k |      548.1k |   +54% |        3795 |       3295 |
| complex    | utf8   |       384.9k |      531.5k |   +38% |        3795 |       3295 |
| repeated   | string |        88.4k |      102.8k |   +16% |       15266 |      14466 |
| repeated   | utf8   |        87.7k |      105.8k |   +21% |       15266 |      14466 |
| struct     | string |       370.0k |      367.1k |    -1% |        7692 |       7692 |
| struct     | utf8   |       348.6k |      356.3k |    +2% |        7692 |       7692 |
| any        | string |       488.7k |      509.2k |    +4% |        1946 |       1946 |
| any        | utf8   |       493.5k |      469.3k |    -5% |        1946 |       1946 |

geometric mean: **+45.1%** over 18 cases

## 3. Proto3 JSON compatibility

The fast path is an optimization, not a second parser, so the question is only whether it can ever return something the previous decoder would not. It cannot, by construction, and the tests try to break the construction:

- *Only canonical input is accepted.* Anything with more than one reading (`1.0` or `"1"` for an int32, `1e2`, leading zeros, a unicode-escaped member name, whitespace between quote and colon, an unknown enum *name*, a timestamp with an offset, `+1`) bails and is decided by the previous decoder, which owns the spec latitude (quoted 32-bit integers, integral floats, URL-safe base64, …).
- *Accepted input is converted exactly.* Integers against `BigInteger` arithmetic (300,000 random tokens plus every boundary, at every alignment); doubles against `Double.parseDouble` bit for bit (tens of millions of cases: every exponent × every digit count, shortest forms of random doubles, exact halfway cases, subnormals, overflow); floats against `Float.parseFloat`; strings against the JDK's strict UTF-8 decoder, including damaged input and lone-surrogate escapes; Latin-1 `String` bytes are characters, never UTF-8.
- *Differential tests.* `BuffJsonFastPathTest` decodes canonical output of `JsonFormat` and of BuffJson (compact, pretty, proto names) for random messages of 16 message types, including the official `TestAllTypesProto3`: every document must be *accepted* by the fast path (otherwise it would silently never run) and equal the original. It then damages documents — thousands of random mutations, every single-byte change to every member name, truncation, slices — and requires the same message or the same exception type with the fast path on and off, and that whenever `readFast` accepts a document the previous decoder accepts it with an equal result.
- *Mutation checks.* Injected defects in the name matcher, string scan, integer and double code and the generator (a name's last byte unchecked, backslash ignored in the scan, leading zeros accepted, wrong rounding shift, wrong multiplier, missing whitespace skip, missing depth guard, wrong `NullValue` code) are each caught.

Spec gaps noticed and deliberately left as they were, because the fast path must not change results: unknown members are skipped (`JsonFormat` rejects them unless told to ignore them); two members of one `oneof` are accepted and the last wins (`JsonFormat` rejects that); a `null` element of a repeated field of anything but `Value`/`NullValue` is skipped by the runtime paths and rejected by generated decoders (`JsonFormat` rejects it). All three are the previous behaviour of every path and are reproduced exactly by the fast path.

Not run here: the official `conformance_test_runner` binary (it is built from protobuf sources in CI; the `buff-json-conformance` testee compiles and the runner's own message, `TestAllTypesProto3`, is exercised by the tests above). CI should run the `conformance` job on this branch before merging.

## 4. Risks and costs

- **Generated code grows.** Decoder classes of the benchmark protos went from 60 KB to 138 KB (16 classes), i.e. roughly 2.3×, for name dispatch plus `readFast`. A plugin option to omit `readFast` is a small follow-up if footprint matters.
- **Reliance on fastjson2 internals**: `JDKUtils.STRING_CODER`/`STRING_VALUE` (public but utility-level) to view a Latin-1 `String`'s bytes, and `JSONReader.nextIfName4MatchN`/`getRawInt` in the general decoder. Both are guarded — when unavailable the String fast path or the exact-name route is skipped and nothing else changes — and CI should also run against the newest fastjson2 (2.0.64/2.0.65 are out).
- **JIT sensitivity.** Whether the builder of a message is scalar-replaced depends on inlining decisions (observed: 226 vs 354 bytes/op for the same code after an unrelated change, with no throughput loss). The design keeps every method small, but allocation per op is not a stable metric for this path.
- **JEP 498** (JDK 24+): fastjson2 and protobuf-java call `sun.misc.Unsafe` memory-access methods, which print a warning on first use on JDK 25 and will eventually be denied by default. The new code uses `VarHandle` byte-array views only; the general decoder and protobuf are unchanged in this respect.
- **`slowOrdinal`** (the `String` switch behind the exact-name route) is over the JIT's 8,000-byte limit for messages of about 300 members and therefore interpreted; it is only reached for unusual spellings.

## 5. What else could move the needle

Ordered by expected value; none is started, and the gains are estimates from the shapes measured above, not measurements.

1. **Fast readers for `Struct`/`Value`/`ListValue`/`FieldMask`** (or delegating just that value to the general reader and resuming): removes the residual bail cost and should let those documents take a gain like the +20–60% of comparable shapes. `Any` needs the type registry and can stay on the general path.
2. **Runtime path without the plugin** — done, see [runtime-codegen.md](runtime-codegen.md). The `readFast` reader is now described once in a small code model; the plugin prints it as Java and, on Java 24+, the library lowers it to bytecode with the Class-File API on first use of a message class (`BuffJson.decoder().setRuntimeCodegen(true)`, off by default). Measured on this section's benchmark shapes: 1.7× the throughput of the typed decoder (1.3–2.6× per shape) and equal to the plugin's decoder (0.99–1.02×), with a one-time cost of about a quarter of a second for the first message class and 10–30 ms for each further one.
3. **UTF-16 `String` input**: transcode to UTF-8 with an encoder that reports lone surrogates, then take the fast path. Worth about +10% for non-Latin-1 text; only if `String` input matters.
4. **Low-cardinality strings** (country codes, enum-like values): a small direct-mapped cache of `String`s keyed by the bytes saves an allocation per value; +5–10% on such data, none on unique strings.
5. **Adaptive bail**: stop attempting the fast path for a message class that keeps bailing. The measured penalty (±8% on always-bailing shapes) is too small to justify the complexity today.

## 6. Reproducing

```
mvn -B -ntp install -DskipTests
java -jar buff-json-benchmarks/target/benchmarks.jar DecodeFastPathBenchmark -p layout=compact -prof gc   # fast path off vs on
java -jar buff-json-benchmarks/target/benchmarks.jar DecodePathsBenchmark -p generated=true              # generated / runtime decoder
```

`DecodeFastPathBenchmark` is the same build with `fast=false|true`; `DecodePathsBenchmark` uses only pre-existing public API, so its exact bytecode can also be run against an older jar. `DecodeCeilingBenchmark`, `NumberReadBenchmark` and `DecodeOverheadBenchmark` are the experiments behind section 1.

