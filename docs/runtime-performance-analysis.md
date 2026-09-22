Runtime performance investigation, 7 September 2026
===================================================

The strongest immediate opportunities are in runtime decoding: parse nested messages into their concrete builders, and give canonical timestamps a small fast path. For native images, add a MethodHandle accessor backend instead of relying on runtime LambdaMetafactory calls. These recommendations come from isolated prototypes, JMH measurements, JVM tests, and native executables. Production sources were left unchanged during this initial analysis. The nested-builder and timestamp changes were subsequently implemented, along with cached typed builder setters; the measurements below are for the earlier prototypes.

The baseline is commit `7d6799521bb793d731d2a5125cc53a9113ca2278`, with fastjson2 2.0.63 and protobuf-java 4.34.1. Experiments used macOS/aarch64, Corretto 21.0.11 and 25.0.4, and Oracle GraalVM 25.2.4+7.1 (JDK 25.0.4). All prototypes compile with `--release 21`, without preview APIs, private-field access, ASM, or handwritten bytecode.

**Measured decoder changes.** The comparison uses the existing `ComplexMessageDecodeBenchmark.buffJsonRuntime`: 1,024 seeded messages containing nested messages, repeated fields, maps, bytes, two timestamps, enums, and a oneof. JMH runs were serial, single-threaded, with two forks, four one-second warmups and five one-second measurements per fork, plus `-prof gc`. Alternative classes were placed before the unchanged baseline benchmark jar on the classpath.

|  Runtime decoder variant   |   Java 21 ops/s | Java 21 bytes/op |   Java 25 ops/s | Java 25 bytes/op |
|----------------------------|----------------:|-----------------:|----------------:|-----------------:|
| Baseline                   | 287,357 ± 3,907 |           10,168 | 305,203 ± 3,474 |           10,168 |
| Concrete nested builders   | 365,977 ± 2,558 |            7,870 | 363,484 ± 7,471 |            7,870 |
| Canonical timestamp parser | 375,942 ± 4,516 |            6,725 | 389,813 ± 9,282 |            7,006 |
| Both changes               | 501,594 ± 4,130 |            4,708 | 513,897 ± 3,946 |            4,708 |

Errors are JMH’s reported 99.9% confidence intervals.
On Java 21, the combined prototype increases throughput by 74.6% and reduces allocation by 53.7%.
On Java 25, the combined prototype increases throughput by 68.4% and reduces allocation by 53.7%.

These are workload-specific local results, not universal speedups. The final comparison was run separately from native compilation and Maven builds. An earlier exploratory run produced a larger nested-builder gain; the table uses the subsequent controlled comparison. JMH confidence intervals and allocation results are retained in the raw JSON.

**1. Stop constructing and converting DynamicMessage for ordinary nested fields.**

The descriptor-only overload of [ProtobufMessageReader](../buff-json/src/main/java/io/suboptimal/buffjson/internal/ProtobufMessageReader.java) constructs a DynamicMessage. [FieldReader](../buff-json/src/main/java/io/suboptimal/buffjson/internal/FieldReader.java) takes this route for singular, repeated, and map message values. When the parent is a generated builder, protobuf's `SingularMessageFieldAccessor.coerceType` and `RepeatedMessageFieldAccessor.coerceType` then create a concrete builder, merge the DynamicMessage, and build again. This adds a temporary field set and another traversal of the nested content. Allocation samples in the JFR recording point to DynamicMessage/SmallSortedMap construction as a substantial source of waste.

The prototype obtains the child with `parent.newBuilderForField(fd)` and parses directly into it. Map values use the map-entry builder as their parent. This retains the normal scalar parsing and reflective setters, and leaves WKT handling intact. It uses the public [Message.Builder API](https://protobuf.dev/reference/java/api-docs/com/google/protobuf/Message.Builder.html).

This is the first change I would implement. It is small, helps without the protoc plugin, and retains a descriptor-based fallback when the parent itself is dynamic. A production patch should preserve existing generated-decoder dispatch when enabled and available in a mixed generated/runtime object graph; the experiment focuses on forced runtime decoding. Test recursive messages, repeated nested messages, message-valued maps, duplicate fields, nulls, and descriptor identity explicitly. Do not replace the resulting child builder with a process-wide mutable cached builder.

**2. Fast-path canonical Timestamp parsing, then consider Duration.**

[WellKnownTypes.parseTimestamp](../buff-json/src/main/java/io/suboptimal/buffjson/internal/WellKnownTypes.java) currently uses `Instant.parse` for every value. JFR CPU samples include the formatter parser, its context, and parsed-field maps. The prototype recognizes exactly the usual UTC output with 0, 3, 6, or 9 fractional digits, parses digits directly, validates the date with `LocalDate.of`, and converts it to epoch seconds. Other representations and invalid fast-path candidates go through the original `Instant.parse` path, preserving its behavior and error normalization.

Generated decoders call the same helper, so this optimization reaches them as well; the codegen speedup was not measured. It does not affect encoding. The prototype passed 100,000 seeded timestamp cases against JsonFormat output, including all four precisions, plus valid extremes, leap days, malformed dates, offsets, lowercase separators, leap seconds and other fallback cases. Canonical output precision and acceptance of time-zone offsets are specified in the [ProtoJSON format](https://protobuf.dev/programming-guides/json/).

Duration parsing separately creates substrings and pads a fractional string with nine zeros. A digit parser can eliminate those intermediates while retaining sign, range and precision validation. This was inspected but not benchmarked or implemented here. Avoid adding a custom general-purpose RFC 3339 parser when a narrow fast path plus the existing fallback is sufficient.

**3. Make the typed accessor backend work in native images.**

The current encoder's typed schema is populated with dynamic `LambdaMetafactory.metafactory` calls in [TypedFieldAccessorFactory](../buff-json/src/main/java/io/suboptimal/buffjson/internal/typed/TypedFieldAccessorFactory.java). On the tested native executable, schema creation failed and the existing catch/failure sentinel silently selected reflection. A separate direct metafactory probe reported that classes cannot be defined at runtime. Functional round trips alone therefore do not prove the typed path is active.

The alternative backend uses ordinary Java lambdas, compiled by javac, capturing MethodHandles normalized once to signatures such as `(Message)int`. Invocation uses `invokeExact`, keeping primitive results unboxed. This adds no runtime class generation. With registered methods, it kept typed schemas active for both smoke-test message classes and passed String and UTF-8 round trips in the native executable. The baseline reflection and generated paths also passed with the same native configuration.

| Typed encoder, Java 25 | Lambda accessors ops/s | MethodHandles ops/s | Throughput change |
|------------------------|-----------------------:|--------------------:|------------------:|
| complexTypedUtf16      |              1,663,814 |           1,544,248 |             -7.2% |
| complexTypedUtf8       |              1,653,398 |           1,574,692 |             -4.8% |
| simpleTypedUtf16       |             17,629,573 |          15,691,614 |            -11.0% |
| simpleTypedUtf8        |             20,213,908 |          16,783,787 |            -17.0% |

These measurements favor retaining LambdaMetafactory for HotSpot with a MethodHandle backend for native images. The MethodHandle prototype loses 4.8–17.0% throughput on the four tested HotSpot shapes, with effectively unchanged allocation. Select the native backend explicitly when running in an image, rather than first attempting unsupported dynamic class generation for every schema. A single-type microbenchmark is insufficient: the actual encoder loops over heterogeneous accessors, and both the accessor call site and the getter target can become polymorphic. Native performance itself has not been benchmarked here.

Native deployment still requires metadata for application message classes and the methods used by both buff-json and protobuf's own FieldAccessorTable. A schema-resource glob does not register those reflective methods. Provide an optional native integration module/Feature or a documented tracing-agent workflow; the protoc plugin can additionally emit metadata or registration source. Keep descriptors and compiled classes reachable at image build time. GraalVM documents these requirements for reflection and MethodHandles in its [reachability metadata guide](https://www.graalvm.org/jdk25/reference-manual/native-image/metadata/).

There is also a dependency configuration issue: fastjson2's bundled initialization list failed to build on this GraalVM release, first for `JSONFactory$CacheItem`, then `ParameterizedTypeImpl` and `ObjectWriterBaseModule`. The smoke images were built successfully with `--initialize-at-build-time=com.alibaba.fastjson2` and tracing-agent metadata. That package-wide initialization is an experimental configuration used to establish feasibility, not a finished library deployment recipe. Resolve and test the narrow supported initialization policy before claiming general native-image support. Builds used `--exact-reachability-metadata`, and executions used `-XX:MissingRegistrationReportingMode=Exit` so caught missing-registration errors could not hide a failure.

**4. Add typed decoding plans after removing the larger avoidable work.**

There is currently no typed runtime decoder equivalent to TypedMessageSchema. Scalar values pass through Object and `builder.setField`, repeated values use `addRepeatedField`, and maps build a protobuf entry for each JSON member. A cached decode plan can bind public `setXxx`, `setXxxValue`, `addXxx` and `putXxx` methods, along with builder construction. Primitive setter interfaces or exact MethodHandle signatures can avoid boxing, while typed map insertion removes temporary map-entry messages.

Reuse FieldReader's scalar/WKT helpers, name alias rules, unknown-enum handling, null semantics and error contract. Use per-field fallback for unsupported accessors rather than abandoning an entire message. Key concrete accessor plans by class; descriptors remain necessary for DynamicMessage. Do not store type-registry-specific reader state in a global schema. This is a larger follow-up design, not a measured implementation or a promised codegen-equivalent speedup.

**Other encoder opportunities, in priority order.**

- Preselect map key/value and repeated float/double/bool writers in the schema. TypedMapAccessor still enters FieldWriter's descriptor/type switches for every value, and several repeated types use the generic loop. Measure map-heavy and homogeneous primitive workloads before accepting the extra accessor variants.
- Cache ordinary nested message plans without repeating the full dispatch and schema-map lookup at every child. Handle recursive schemas lazily, preserve generated/runtime flags, and avoid cross-class or cross-classloader assumptions. For oneofs, a bound public case getter could replace `getOneofFieldDescriptor` plus a linear scan; preserve presence and output ordering.
- Bound enum name-table size by both maximum value and density. `buildEnumNames` currently allocates `new String[max + 1]`; a valid sparse positive enum can consume excessive memory or fail schema creation. Use a modest dense prefix with sparse lookup for the remainder. Apply the same policy to generated enum tables.
- Consider ClassValue for concrete class metadata and inspect descriptor-cache retention for applications that create descriptors or unload classloaders. The existing memory tests check retention of message instances, not unloading of classes/descriptors. A weak-key map whose values strongly retain their keys would not solve that problem.

Field-name hashing is a secondary experiment. The runtime decoder currently reads a String and looks it up in a HashMap, and JFR finds field-name reading on the CPU path. However, fastjson2 already caches short ASCII names; there is no allocation per name in every case. `readFieldNameHashCode` plus a primitive lookup table may help longer or less-cacheable names. Preserve exact name equality, both JSON/proto aliases, escapes and unknown-field behavior; schema-internal collision detection alone does not protect against an unknown input name colliding with a known field. Do not accept a hash-only dispatcher without an exact-match design and adversarial tests.

The repeated-int experiment uses protobuf's public `Internal.IntList.getInt` rather than `List<Integer>.get`. The existing mixed repeated benchmark already shows essentially identical codegen/runtime allocation, consistent with boxing being removed by HotSpot in this workload. The repeated-int prototype measured 190,954 ± 4,273 ops/s, against 190,140 ± 1,960 ops/s for the baseline. Both allocated approximately 5,385 bytes/op; the throughput confidence intervals overlap. This provides no convincing gain for this workload. It should not outrank decoder work merely because the source visibly boxes.

Bytes decoding currently allocates a String, a decoded byte array, and a ByteString copy. An upstream fastjson2 byte-slice/base64 API would help both decoders. `JSONReader.readBinary()` is not a drop-in strict ProtoJSON reader in the pinned version: its accepted forms and empty-input behavior differ. Treat `UnsafeByteOperations.unsafeWrap` as an explicit ownership tradeoff, not the default optimization; retaining immutable-message guarantees is more valuable than saving one copy without a proven ownership contract.

**Correctness and compatibility gates.**

The unchanged baseline passed all 496 Maven tests on both Java 21 and 25. The combined nested-builder/timestamp prototype passed the same suite on both JVMs; adding the MethodHandle backend also passed on both. The native checks were targeted smoke tests, not the entire native test suite. Java 22, 23 and 24 were not installed and were not executed. The official C++ conformance runner was not available locally and was not run; Maven verify builds its testee but does not execute that runner. Existing CI enforces the official suite against the curated failure list, which includes accepted deviations. Passing that list is not the same as zero conformance exceptions.

Some existing scalar cases also diverge from JsonFormat despite the green Maven suite. Both baseline decoders accepted `{"id":1.0000000000000001}` as 1, `{"id":1e-400}` as 0, and `{"timestampMillis":true}` as 1 in SimpleMessage; JsonFormat rejected all three. The first two expose rounding in the current double-based integral check, and the third exposes coercion in the unquoted int64 path. Add these as regression cases before changing numeric fast paths. A faster parse must not trade away exactness, unsigned bounds or token-type validation.

Before shipping, run correctness tests on Java 21–25, the enforced official conformance runner on all supported paths, and native tests with strict metadata checking that assert which accessor backend actually ran. Extend allocation/performance CI to decoding: it currently concentrates on encoding, even though the performance workflow covers Java 21 and 25. Include String and UTF-8, scalar and collection-heavy shapes, DynamicMessage and messages without buff-json codegen, WKTs/Any, escaped and long names, cold schema construction and warm steady state. Keep the existing negative-zero, unknown enum, explicit presence, null, range and malformed-input tests.

The implementation order I recommend is nested builders, canonical Timestamp/Duration helpers, native accessor support with a tested initialization/metadata recipe, then typed setter/map plans. Retain the current serializer path structure while measuring each change independently. No preview-only Java API or manual bytecode manipulation is needed for these improvements.

**Reproduction artifacts.** Local machine-specific evidence and experimental patches are saved under [benchmark-reports/runtime-analysis-2026-09-07](../benchmark-reports/runtime-analysis-2026-09-07/). That directory is intentionally ignored by Git under the existing repository policy. It includes benchmark JSON/logs, prototype patches, native configuration/results, timestamp and scalar probes, and the experiment/measurement scripts. The patches are reviewable experiments, not production changes ready to merge.
