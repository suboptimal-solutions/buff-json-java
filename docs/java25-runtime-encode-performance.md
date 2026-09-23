# Java 25 runtime encoder performance plan

22 September 2026

## Scope and conclusion

This note considers protobuf-to-JSON **encoding without the buff-json protoc
plugin**, on HotSpot 25 or newer. It does not propose making `DynamicMessage`
depend on generated-class internals. The current typed-accessor path is already
the right fallback architecture, but it cannot normally equal the protoc path:
it executes a heterogeneous `TypedFieldAccessor.write` loop, invokes one or
more lambda objects per field, and re-enters runtime dispatch for nested
messages. The generated path instead presents C2 with one straight-line,
type-specific method.

The baseline changes the recommendation from the initial source-only analysis:
**do not start by shipping a runtime bytecode compiler**. The typed path is
already statistically indistinguishable from codegen for complex UTF-16 and
Struct on this run, while its clearest deficits are shape-specific. First
specialize maps, oneofs, missing repeated primitives, nested/WKT dispatch, and
UTF selection in the existing typed plan, measuring each change. Then profile
the remaining simple-scalar gap. A Java 25 Class-File API hidden encoder remains
a bounded experiment only if dispatch is still proven dominant. Direct
`Unsafe` field access is not recommended.

The recommendations below combine source-path inspection with a Java 25
baseline covering all 30 `EncodePathsBenchmark` cases and all 36
`DecodePathsBenchmark` parameter combinations. The complete protocol, tables,
and allocations are in the
[baseline report](performance-results/2026-09-22-java25-encode-decode-baseline.md),
with the underlying summary data in
[CSV form](performance-results/2026-09-22-java25-encode-decode-baseline.csv).
Optimization gains remain hypotheses until a same-host before/after JMH run
validates them.

## Where the runtime path still pays

For every concrete message, `ProtobufMessageWriter.writeFields` obtains the
descriptor and class, looks up `TypedMessageSchema`, and iterates a
`TypedFieldAccessor[]`. Every array element reaches a different record
implementation through the same interface call site. Each record then invokes
a `Function`, `Predicate`, or primitive function produced by
`LambdaMetafactory`. HotSpot can inline individual lambda targets, but the
outer loop becomes polymorphic or megamorphic for realistic schemas. In
contrast, a generated encoder has a distinct bytecode call site for each typed
getter and each write operation.

The remaining work is shape-dependent:

1. **Small scalar messages:** interface/lambda dispatch and repeated
   `JSONWriter.isUTF8()` checks are candidate costs, but profiling must separate
   them from writer setup and result materialization.
2. **Nested messages:** each child goes through `writeMessage`, descriptor and
   schema-cache dispatch again.
3. **Maps and repeated fields:** only repeated int, long, string, enum, and
   message values have dedicated typed loops. Other primitive collections and
   map values still enter descriptor-driven `FieldWriter` switches.
4. **Bytes:** `ByteString.toByteArray()` allocates and copies before
   `JSONWriter.writeBase64(byte[])`, even when the `ByteString` already owns a
   suitable byte array.
5. **Final output:** returning `String` or `byte[]` necessarily materializes the
   result. For large payloads, JSON writing and this final copy dominate any
   accessor optimization; the existing `OutputStream` overload should be
   benchmarked separately.

On the measured Java 25 baseline, typed runtime encoding delivered 67.7–102.4%
of codegen throughput depending on shape and output. The clearest gaps were
simple UTF-8 (67.7%) and map UTF-16 (70.0%); complex UTF-16 and both Struct
means had overlapping confidence intervals. This supports targeting scalar
dispatch and map specialization first, rather than assuming every message
shape is accessor-bound.

## Priority 1: specialize the existing typed plan

These changes are lower risk, target the measured shape-specific gaps, and
remain useful even if a runtime compiler is later justified:

1. Give typed `Timestamp` and `Duration` fields direct seconds/nanos strategies,
   matching the existing codegen helpers instead of entering generic
   `WellKnownTypes.write` descriptor access. Timestamp runtime is consistently
   about 79% of codegen in both outputs, making this a narrower and better
   supported first experiment than whole-message compilation. Preserve generic
   WKT fallback for custom implementations and cover repeated/map WKT values.
2. Build typed map writer strategies once. Today `TypedMapAccessor` avoids
   reflective map extraction but still dispatches key/value behavior by
   descriptors for every entry. Separate string, boolean, signed integer, and
   unsigned integer key writers, then value writers for every scalar, enum,
   bytes, WKT, and ordinary message shape. Map UTF-16 is the most relevant
   baseline gate; require improvement in UTF-8 too and no complex regression.
3. Bind the generated oneof case getter once. A case-number accessor plus an
   indexed member table can avoid descriptor lookup and linear matching on
   every encoding.
4. Add dedicated repeated accessors for `float`, `double`, `boolean`, and
   `ByteString`. Use protobuf primitive-list APIs only when measurement shows a
   benefit, and retain a normal `List` fallback for custom generated code.
5. Cache ordinary nested runtime plans so children do not repeat descriptor and
   concurrent-map lookup. Resolve recursively and lazily; preserve generated,
   runtime, `DynamicMessage`, WKT, and polymorphic fallbacks.
6. Hoist `isUTF8()` out of `FieldName.writeTo` by selecting UTF-8 or UTF-16 at
   `TypedMessageSchema.writeFields`. Measure a boolean parameter before
   duplicating accessor methods.
7. Avoid abandoning an entire typed schema when one unusual accessor cannot be
   bound. Use a per-field reflection accessor so ordinary fields retain the
   fast path.

Implementation status: items 1 and 6 are now implemented. Typed schemas hoist
the writer encoding once per message, and singular/repeated concrete Timestamp
and Duration fields use their direct primitive formatting helpers with a safe
generic-WKT fallback. Typed map strategies are the next isolated change.

Earlier repeated-int investigation found no convincing gain from merely
replacing `List<Integer>.get` with `Internal.IntList.getInt`. Primitive-list
source aesthetics are not evidence; specialization must remove measured
dispatch or allocation. Likewise, the baseline ratios identify where to
investigate, not which instruction is responsible.

## Priority 2: profile and impose a compiler decision gate

After each typed-plan change, rerun baseline and candidate on the same Java 25
host in alternating order. Use JFR or async-profiler plus a narrowed
`-XX:+PrintInlining` run for simple UTF-8. Proceed to runtime class generation
only if all of the following hold:

- typed runtime remains at least 15% slower than codegen on two relevant shapes
  with non-overlapping confidence intervals;
- profiles attribute a material share to the heterogeneous accessor loop or
  lambda calls rather than JSONWriter setup, escaping, number formatting,
  Base64, or final output materialization;
- the expected steady-state saving repays first-use generation and C2
  compilation for the application's message lifetime; and
- generated-code size and class unloading can be bounded and tested.

The current baseline meets the throughput-gap condition for simple UTF-8 and
map UTF-16, but it does **not** establish the causal/profile, cold-cost, or code
cache conditions. It is therefore insufficient evidence to make hidden classes
the default architecture.

## Priority 3: prototype a whole-message encoder only if the gate passes

Use `java.lang.classfile` (final in Java 25) to build an implementation of an
internal runtime-encoder interface, then define it with
`MethodHandles.Lookup.defineHiddenClass`. Generate one straight-line
`writeFields(JSONWriter, Message, ProtobufMessageWriter)` method with:

- a single cast from `Message` to the concrete generated type;
- one `isUTF8()` decision, with either two complete method variants or one
  hoisted boolean;
- direct `invokevirtual` calls to public `getXxx`, `hasXxx`, `getXxxList`,
  `getXxxMap`, and `getXxxValue` methods;
- constants for encoded field names, enum tables, unsigned flags, and WKT
  classification;
- inline default/presence checks and scalar JSONWriter calls;
- a oneof switch based on the generated `getXxxCase().getNumber()` method,
  rather than `getOneofFieldDescriptor` plus an accessor scan; and
- specialized loops for all repeated and map key/value types.

This would be runtime code generation, but not protobuf code generation: users
would not run a plugin and no generated source or service entry would be
needed. Public getters mean the hidden class does not need private access to
the application message class. Cache the plan by concrete class using
`ClassValue`; verify that the descriptor supplied at construction matches
instances at invocation. `ClassValue` allows class-loader unloading, unlike a
process-wide map whose values retain classes or descriptors.

### Bootstrap and failure behavior

Compilation is a cold-path cost and must happen once. A per-class state should
distinguish `BUILDING`, `READY`, and `FAILED`, permit recursive descriptors,
and publish the finished encoder safely. On any linkage, access, or unexpected
protoc-shape failure, retain the current typed schema as the fallback. Do not
silently fall all the way to descriptor reflection when typed access remains
valid.

Hidden classes are intentionally undiscoverable and unload with their lookup
context, but the cache design still matters. The generated encoder must not
capture a `BuffJsonEncoder`, `TypeRegistry`, message instance, or mutable
`JSONWriter`. Those remain invocation parameters. A configurable maximum
compiled bytecode size should fall back for pathological schemas so a huge
message does not create an oversized C2 compilation unit.

### Nested messages

Start by calling `writer.writeMessage` for nested values; this already benefits
when the child plan is cached. Then test an inline cache in the runtime encoder:
cache the expected child class and its runtime encoder, and call it directly
between `startObject`/`endObject`. A polymorphic fallback is required because a
message-typed field can contain generated subclasses or unusual
implementations. Recursive types require lazy child resolution rather than
eager graph construction.

## Priority 4: do not use `Unsafe` for protobuf field access

Direct reads of generated protobuf fields look attractive, but are a poor
general foundation:

- generated storage is not the public protobuf ABI and changes by protoc mode
  and version;
- strings may be stored as either `String` or `ByteString` and getters perform
  lazy conversion/caching;
- presence is encoded in implementation-specific bit fields;
- enums, oneofs, maps, repeated fields, extensions, and lite/full runtimes have
  different representations;
- bypassing getters can observe representation rather than protobuf semantics;
  and
- Java 25 warns on terminally deprecated `sun.misc.Unsafe` memory-access
  methods, with stronger denial planned for later releases.

More importantly, getter bodies are generally tiny and C2 can inline them at
monomorphic call sites. Unsafe field offsets do not solve the larger
heterogeneous-accessor dispatch problem. Even if the compiler decision gate
passes, use public getters in the prototype. Private field loads should remain
out of scope unless assembly proves a getter itself remains material, which is
unlikely.

### The only defensible Unsafe experiment: bytes

Bytes are different because the current path visibly calls
`ByteString.toByteArray()`. First seek or contribute a public fastjson2/protobuf
slice bridge. Only if bytes-heavy profiling proves the copy material and no
public API can remove it should an opt-in backend recognize protobuf's known
literal and bounded byte-string implementations, obtain backing array, offset,
and length, and call a Base64 method accepting a slice. Rope or unknown
implementations must fall back to a copy. This requires strict class/version
guards and differential tests; exposing a whole backing array to a method that
lacks offset/length is incorrect for bounded values.

If an Unsafe prototype is run on Java 25, make the opt-in explicit and include
the VM policy in the benchmark record, for example:

```text
--sun-misc-unsafe-memory-access=allow
```

Add `--add-opens` only if the chosen implementation actually reflects into a
non-open package; do not prescribe broad `--add-opens=...=ALL-UNNAMED` flags by
default. The library must probe support once, report why the requested backend
cannot activate, and otherwise fall back safely. Never require Unsafe for
correctness.

## Secondary opportunities

- **Writer reuse:** investigate an explicitly thread-confined encoder session
  that reuses a resettable JSONWriter buffer. The public shared encoder must not
  cache a writer, and retained peak buffers need a cap. Confirm fastjson2's
  existing buffer cache before adding another layer.
- **Output sizing:** schema-derived pre-sizing may help large predictable
  payloads, but scanning strings/repeated values twice will often lose. Prefer
  adaptive retained capacity in a session if measurement supports it.
- **Streaming:** the `OutputStream` overload avoids retaining the final result,
  but fastjson2 still buffers before `flushTo`. A genuinely chunked writer or
  direct sink integration is more valuable for very large messages than
  shaving getter calls.
- **Enum storage:** cap dense enum-name arrays and use a sparse secondary table.
  This is primarily robustness, but prevents a sparse enum from failing runtime
  plan construction and forcing the slow reflection path.
- **Cache lookup:** move typed metadata from descriptor-keyed global maps to a
  class-keyed `ClassValue` front end, retaining descriptor validation. This
  replaces a concurrent-map lookup in the hot entry path and improves
  class-loader behavior.

## Measurement and acceptance plan

Do not merge a typed optimization, runtime compiler, or Unsafe backend on a
single favorable microbenchmark. On Java 25, compare codegen, current typed
runtime, the candidate typed runtime, and reflection. Add the runtime compiler
as a fifth path only if the decision gate is met. Cover:

- simple scalars, all scalar types, complex nested messages, deep recursion,
  maps, repeated primitives, bytes, WKT/`Any`, oneofs, and sparse enums;
- UTF-16 `String`, UTF-8 `byte[]`, and `OutputStream` output;
- warm steady state and cold first-use cost;
- monomorphic and mixed message classes at the same encode call site;
- one and multiple threads; and
- throughput, `gc.alloc.rate.norm`, generated-code size, compilation time, and
  retained class metadata.

Use at least two forks and enough warmup for C2 compilation; if hidden classes
are tested, separately report cold generation and first-encode latency. Save
JMH JSON and the exact `java -version`, CPU, commit, and VM arguments. Use JFR
or async-profiler to establish whether accessor dispatch, Base64 copying,
number formatting, escaping, or final materialization is actually dominant.
Inspect inlining with `-XX:+PrintInlining` on a narrowed benchmark; a faster
score without proof that the intended backend activated is not sufficient.

Correctness gates are the full Maven suite and official ProtoJSON conformance
suite on the runtime path. Add differential tests against the current typed
encoder for randomized messages, negative zero, non-finite values, unknown and
negative enums, explicit presence, aliases, maps, oneofs, recursive messages,
all `ByteString` shapes, and mixed class loaders. Add a test-only diagnostic
that exposes the active backend so fallback cannot make a test pass unnoticed.

## Recommended implementation sequence

1. Keep the recorded Java 25 baseline, add backend-activation diagnostics, and
   collect JFR/inlining evidence for simple UTF-8 and map UTF-16.
2. Keep the implemented direct typed Timestamp/Duration strategies and hoisted
   UTF selection only while full-matrix controls remain clean.
3. Implement typed map strategies, then measure them against map, complex, and
   simple controls.
4. Optimize oneof selection, missing repeated types, nested-plan lookup, and
   UTF selection as separate changes. Retain only individually demonstrated
   wins.
5. Rerun the complete matrix. If the runtime-compiler decision gate is not met,
   stop: the maintenance and cold-start costs are unjustified.
6. If the gate is met, prototype a scalar-only Class-File API hidden encoder
   behind an internal experimental switch. Measure cold and steady state before
   adding presence, collections, nesting, or WKTs.
7. Profile bytes-heavy payloads. Prefer a public slice API; consider guarded
   Unsafe access only if the copy is demonstrably important and explicitly
   enabled.
8. Consider writer sessions or streaming only from large-message profiles.

This revised sequence spends complexity in proportion to measured evidence,
preserves protobuf's public semantics, and keeps runtime compilation and unsafe
compatibility risk out of the default path until each clears an explicit gate.
