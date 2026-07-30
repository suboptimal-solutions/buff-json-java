# Where encoding time still goes, and what is left to reclaim

An audit of the three encode paths (codegen, typed-accessor, pure reflection) against what
fastjson2 and protobuf-java actually make available. Part 1 is what this branch changed and
measured. Part 2 is the ranked backlog, with the reasoning for each item so the next person
does not have to re-derive it.

Everything here is about *encoding*. The decode path was not audited.

## Method

`./run-benchmarks.sh` numbers are not comparable across sessions on shared/virtualised hosts —
the baseline run for this audit showed score errors up to 40% of the score with `-f 1 -i 3`. All
figures below come from an interleaved A/B: two `benchmarks.jar` builds (before/after) from the
same reactor, run back to back on the same host with identical JMH settings
(`-f 3 -wi 3 -i 5 -r 1 -w 1`), `JsonFormat` baselines excluded to keep the run short.

Treat single-digit-percent deltas here as noise. The items worth acting on were the ones that
showed up as double-digit, or that removed an allocation the `gc.alloc.rate.norm` profiler could
confirm.

## Results

Throughput, ops/s, higher is better, from the final interleaved A/B (after the review fixes below).

|             benchmark             |    before |      after |              delta |
|-----------------------------------|----------:|-----------:|-------------------:|
| `SimpleMessage.compiledUtf16`     | 7,348,223 |  8,455,279 |         **+15.1%** |
| `SimpleMessage.compiledUtf8`      | 8,927,071 | 10,282,569 |         **+15.2%** |
| `SimpleMessage.runtimeUtf16`      | 5,228,166 |  5,324,573 |              +1.8% |
| `SimpleMessage.runtimeUtf8`       | 5,898,804 |  6,286,012 |              +6.6% |
| `ComplexMessage.buffJsonCompiled` |   603,853 |    636,926 |              +5.5% |
| `ComplexMessage.buffJsonRuntime`  |   504,190 |    523,610 |              +3.9% |
| `Wkt.structCompiled`              |   256,180 |    554,431 |          **+116%** |
| `Wkt.structRuntime`               |   254,471 |    528,181 |          **+108%** |
| `Wkt.timestampCompiled`           | 3,940,234 |  3,806,456 |              −3.4% |
| `Wkt.timestampRuntime`            | 1,982,941 |  2,145,355 |              +8.2% |
| `RepeatedAndMap.*` (4)            |         — |          — | flat, within noise |

**Do not read the absolute numbers as machine-independent.** An identical earlier A/B on this same
host, before other work loaded it, put `SimpleMessage.compiledUtf16` at 9.87M → 11.07M — ~25%
higher on *both* sides. The ratios were stable across the two runs (+12.1% then +15.1%); the
absolutes were not. Struct's ratio moved more (+57% then +116%) because its win is
allocation-dominated, and allocation costs more when the host is busier. Only ever compare two jars
run back to back.

Allocation, `gc.alloc.rate.norm` B/op, lower is better (stable across runs):

|             benchmark             | before | after |      delta |
|-----------------------------------|-------:|------:|-----------:|
| `SimpleMessage.compiledUtf16`     |    296 |   208 |        −88 |
| `SimpleMessage.compiledUtf8`      |    272 |   184 |        −88 |
| `SimpleMessage.runtimeUtf16`      |    296 |   208 |        −88 |
| `SimpleMessage.runtimeUtf8`       |    272 |   184 |        −88 |
| `ComplexMessage.buffJsonCompiled` |  1,532 | 1,444 |        −88 |
| `ComplexMessage.buffJsonRuntime`  |  1,396 | 1,308 |        −88 |
| `Wkt.struct*`                     |  1,305 |   648 |   **−657** |
| `Wkt.timestampCompiled`           |    464 |   376 |        −88 |
| `RepeatedAndMap.mapRuntime`       |  8,021 | 6,727 | **−1,294** |
| `RepeatedAndMap.repeated*`        |  5,385 | 5,297 |        −88 |

The flat −88 B/op everywhere is the per-call `JSONWriter.Context`. For `SimpleMessage` that is 30%
of the total — after the change the returned `String`/`byte[]` is essentially the only thing
allocated per encode. Budgets in `allocation-check.sh` were tightened to match.

## Part 1 — Applied

### 1. Word-packed field names (codegen)

**The finding.** fastjson2's `JSONWriter` exposes a `writeName2Raw(long)` …
`writeName16Raw(long, long)` family that takes the field name *already packed into machine
words* and stores it with one or two `Unsafe.putLong` instructions. It is what fastjson2's own
ASM and `@JSONCompiled` writers use, and it was the single largest structural difference between
buff-json's generated encoders and the fastjson2 POJO ceiling that `CeilingBenchmark` measures.

buff-json was using `writeNameRaw(byte[])` / `writeNameRaw(char[])`, which per field costs a
static array load, an array length read, an `arraycopy` of the name, **and** an `isUTF8()` branch
to choose between the two arrays.

Two things make the replacement unusually clean:

- The packed words are just native-order machine-word reads out of the literal `"name":` text,
  zero-padded — offset 0 for every length except 8 and 16, where fastjson2 emits the opening
  quote itself and the word holds only the name body. (Derived empirically for lengths 2–16 on
  both writer encodings; `FieldNames` documents the table.)
- **`JSONWriterUTF16` consumes the same constants**, widening the packed ASCII to `char`s on the
  way out. So a packed name needs no `isUTF8()` branch at all — the branch and the second array
  disappear rather than moving.

Protobuf field names are `[A-Za-z_][A-Za-z0-9_]*` and `getJsonName()` lowerCamelCases them, so
essentially every field is packable. `FieldNames.isPackable` gates on 2–16 printable ASCII with
nothing needing JSON escaping; longer or 1-character names fall back to the pre-encoded arrays.

**Codegen only, deliberately.** Codegen emits the exact call
(`jsonWriter.writeName6Raw(NAME_SCORE_W0)`) because it knows each name's length at
code-generation time — no dispatch remains at all. The first attempt also routed the two runtime
paths through it, with `FieldName` switching on the packed length. That measured *worse*: flat on
`SimpleMessage` and **−10.8% on `ComplexMessage.buffJsonRuntime`**. The switch is an indirect
branch in the hottest code, keyed on a per-field instance value, and `ComplexMessage` has fifteen
fields spanning nine distinct name lengths — enough target variety to defeat the branch predictor
by more than the `arraycopy` it saves. Reverting the runtime paths to the pre-encoded arrays turned
that −10.8% into +2.6% and also moved `SimpleMessage.runtimeUtf16` from −0.4% to +7.7%. The
reasoning is recorded in `FieldName`'s javadoc so it does not get "fixed" again.

Where the win lands: +12–13% on `SimpleMessage` codegen, which is six fields and ~80 bytes of
output — i.e. the shape where field-name writing is the largest single cost.

**Where the packed words are not usable.** The fast path is only partly ours, and two things
invalidate it — both found by review after the first implementation, both now gated in one place
(`ProtobufMessageWriter.canUsePackedNames`, one check per message, falling back to the typed tier
which the fuzz test proves is byte-identical):

- **`UseSingleQuotes`.** The quote characters are split between us and fastjson2, and the split
  differs per length: both quotes are baked into the word for lengths 2-6 and 9-14, only the
  opening quote for 7 and 15, neither for 8 and 16. fastjson2 emits its share from `this.quote`,
  so a single-quote writer produced `{"optionalFixed32':7}` — **unparseable**, verified. The
  `writeNameRaw` arrays had always *ignored* the feature and emitted valid double-quoted names, so
  this was a regression from "ignores a feature" to "emits garbage", not a pre-existing gap.
- **Byte order.** `JSONWriterUTF8` stores the word with `Unsafe.putLong` (native order), but
  `JSONWriterUTF16.putLong(char[], int, long)` widens it by extracting bytes at hard-coded
  little-endian positions (`v & 0xFF`, `(v & 0xFF00) << 8`, ...) with **no `BIG_ENDIAN` branch** —
  confirmed by disassembly. One constant cannot satisfy both on a big-endian host, so the original
  javadoc claim that native-order packing is endian-agnostic was wrong.

`FieldNames.PACKED_NAMES_SUPPORTED` therefore self-checks the layout at class init: it packs a
probe name of every length 2-16, writes it through both encodings, and compares the text. That
turns a big-endian host *or* a future fastjson2 that moves the layout from silent corruption of
every field name into a clean fallback.

*Not supported, before or after:* JSONB output. `JSONWriterJSONB.writeNameRaw(byte[])` forwards to
`writeRaw(byte[])`, which dumps JSON *text* bytes into a JSONB stream — so routing a protobuf
message through `JSONB.toBytes` via `writerModule()` produced garbage already.

### 2. No boxing on repeated primitives (codegen + typed path) — no measured win

`message.getFooList()` for a repeated numeric field returns a `List<Integer>` whose runtime type
is `com.google.protobuf.IntArrayList`, and `IntArrayList.get(i)` returns
`Integer.valueOf(getInt(i))` — apparently an allocation for every element outside the −128..127
cache. The `Internal.IntList` / `LongList` / `DoubleList` / `FloatList` / `BooleanList` interfaces
are public and expose `getInt(i)` / `getLong(i)` / …, so an `instanceof` narrow removes the boxing
*and* the unbox; a generic `List` branch is kept for non-protobuf list implementations.

**It measured as exactly nothing** — 0 B/op and 0% on `RepeatedAndMap`, whose `ints` field holds
50–200 values from `rng.nextInt()` (so essentially none are cache hits). HotSpot was already
scalar-replacing the boxes: `IntArrayList.get` inlines, the `Integer` never escapes the loop, and
escape analysis deletes it. If boxing were really allocating there, it alone would have been
~2,000 of the 5,385 B/op measured.

Kept anyway, but only for one reason: escape analysis is a C2 optimization, so the boxing is real
in the interpreter and at C1 — which is where a short-lived process (a CLI, a scale-to-zero
function) spends most of its time. Do not expect it to show up in a steady-state benchmark.
**This is the cautionary tale of the audit** — "obvious" boxing on a hot path is often already
gone.

The codegen half costs generated bytecode, since the primitive and generic loops are both emitted
per repeated field. Worth knowing the ceiling: HotSpot's `DontCompileHugeMethods` refuses to
JIT-compile *any* method over 8,000 bytecodes, at which point the encoder runs interpreted. The
largest generated `writeFields` in this repo is `TestAllTypesProto3JsonEncoder` at **3,240
bytecodes** (~200 fields, the official conformance sample), so there is ample headroom — but a wide
message with many repeated primitive fields is the shape that approaches it, and no benchmark is
near that size. If codegen grows further per field, measure a wide message before assuming.

The typed path also gained `RepeatedDouble/Float/BoolAccessor`; those types previously fell
through to the generic `RepeatedAccessor`, which re-switched on `JavaType` per element.

### 3. Bulk repeated-string writes

`jsonWriter.writeString(List<String>)` writes the whole array — brackets, commas, per-element
escaping — with one capacity check, replacing the hand-rolled
`startArray`/`writeComma`/`writeString`/`endArray` loop.

### 4. Typed `Struct` / `Value` / `ListValue`

`WellKnownTypes.writeValue` called `desc.getOneofs().get(0)` per `Value`, and
`Descriptor.getOneofs()` is `Collections.unmodifiableList(Arrays.asList(oneofs))` — **two
allocations per value written** — followed by `getOneofFieldDescriptor()` and a `switch` on the
field's `String` name.

`Struct`, `Value` and `ListValue` are ordinary compiled classes in protobuf-java, so the writer
now takes a typed path when it has one: `struct.getFieldsMap()` instead of materializing the
synthetic MapEntry list and pulling key/value back out through `getField()`, and
`value.getKindCase()` — an int switch — instead of the descriptor dance. The switch carries a
`default -> writeNull()` arm: a kind added by a future protobuf-java would otherwise write nothing
after the caller has already emitted the name and colon, i.e. `{"k":}`, and javac has no
exhaustiveness lint for switch *statements*. `DynamicMessage` keeps the reflective path, with the
per-entry `getFields(entry, "key", "value")` lookup hoisted out of the loop (it was a cache probe
*per struct entry*). The `kind` oneof is deliberately **not** cached: a strong-keyed `Descriptor`
map would pin the descriptor pool of every schema ever loaded — a real leak for services that
re-parse `.desc` files — to save two allocations on a path only `DynamicMessage` reaches.

**+57%/+64% throughput and −657 B/op** — by far the biggest single win in the audit, and the one
that was easiest to miss, because it was hiding inside a well-known-type helper rather than on the
main field loop.

### 5. Typed WKT wrappers in codegen

An `Int32Value`/`StringValue`/… field went through `WellKnownTypes.write()` → full-name `String`
switch → descriptor cache probe → `getField()` reflection + boxing. The plugin knows the type, so
it now emits `jsonWriter.writeInt32(wrap.getValue())` and friends directly — the same write a
plain field of that type would get.

### 6. Reflection path: `hasField` before `getField`

`writeFields` read `Object value = message.getField(fd)` *before* checking presence, so every
absent `optional` field paid a reflective read and a boxing allocation whose result was
immediately discarded.

### 7. Map key/value descriptors resolved once

`FieldWriter.writeMap` called `findFieldByName("key")` and `findFieldByName("value")` — two hash
lookups — on *every map field write*. Both are now resolved when the schema is built
(`MessageSchema.FieldInfo.mapKeyDescriptor()`, `TypedFieldAccessor.MapAccessor`). Both remaining
callers have a cached schema, so `writeMap` takes both descriptors and the old
`findFieldByName`-resolving overload is gone.

### 8. Non-ASCII `json_name` (a correctness fix found on the way)

Auditing the name path surfaced a latent bug rather than a slow path. `[json_name = "…"]` accepts
any string, and:

- the old `buildNameWithColonUtf8` did `(byte) name.charAt(i)`, truncating anything non-ASCII into
  corrupt UTF-8, and would have emitted a raw `"` or `\` straight into the JSON string;
- `EncoderGenerator` interpolated the name into a Java string literal unescaped, so a `json_name`
  containing a quote or newline generated source that **does not compile**.

`FieldNames.isRawWritable` now gates the raw arrays (printable ASCII, nothing escapable), anything
else goes through `JSONWriter.writeName(String)` + `writeColon()` so fastjson2 escapes and
transcodes it, and `FieldNames.javaStringLiteral` (mirrored in the generator) escapes emitted
literals.

A subtlety worth recording, because the first attempt got it wrong: a line terminator **must** be
emitted as `\n`/`\r`, never as a unicode escape. javac translates unicode escapes *before*
tokenizing (JLS 3.3), so `\u000a` turns back into a real newline and leaves the literal unclosed —
the same uncompilable output, now harder to spot. `FieldNamesTest` compiles the emitted literal
with `javax.tools.JavaCompiler` and reads the constant back, rather than asserting the weaker
"body is printable ASCII" property that a broken `\u000a` satisfies.

### 9. Deprecated fields (a second correctness fix)

The name-constant loop skipped `[deprecated = true]` fields while the `writeFields` loop did not,
so a deprecated field emitted a name write referencing a constant that was never declared —
generated source that does not compile. No `.proto` in the repo has a deprecated field, so CI was
green and this would have broken the first user build that did. Deprecated fields now serialize
like any other (matching `JsonFormat` and both runtime paths), and both generators emit
`@SuppressWarnings("deprecation")` on the class when needed, since consumers build with `-Werror`.

### 10. One `JSONWriter.Context` per encoder

`JSONWriter.of()` allocates a fresh `JSONWriter.Context` per call to carry configuration that
never changes between encodes. `BuffJsonEncoder` now holds one and passes it to
`JSONWriter.of(ctx)` / `ofUTF8(ctx)`. Verified safe to share across writers and encodings; the
context is read-only during writing.

**−88 B/op on every benchmark**, which on `SimpleMessage` is 30% of the total. The cheapest item
in the audit by a wide margin: five lines.

One behavioural caveat, documented on the field rather than left implicit: `JSONWriter.Context`
copies fastjson2's mutable global defaults (`defaultWriterFeatures`, `defaultWriterZoneId`,
`defaultMaxLevel`, `defaultWriterFormat`) at construction. `JSONWriter.of()` re-read them per call,
so a `JSON.config(...)` executed *after* an encoder was built used to take effect on the next
encode and now does not. Configure fastjson2 before constructing encoders.

## Part 2 — Backlog, highest value first

### B1. Devirtualize `JSONWriter` (generate per-encoding `writeFields` bodies)

The remaining big structural item. `writeFields` calls `writeString`, `writeInt32`, `writeDouble`,
`writeBase64`, `startObject`… on `JSONWriter`, an abstract class with six concrete subclasses in
the jar (`JSONWriterUTF8`, `JSONWriterUTF16` + three JDK/unsafe-free variants, `JSONWriterJSONB`).
An application that uses both `encode()` (UTF-16) and `encodeToBytes()` (UTF-8) makes every one of
those call sites bimorphic; add other fastjson2 usage in the same process and the profile can go
megamorphic, at which point HotSpot stops inlining the methods that do the actual work.

Fix: have the plugin emit `writeFieldsUtf8(JSONWriterUTF8 …)` and
`writeFieldsUtf16(JSONWriterUTF16 …)`, with `writeFields` doing one type check and dispatching.
Every write inside then has a concrete receiver.

Cost: 2× generated bytecode per message, and bigger methods can *lose* inlining, so this must be
measured rather than assumed. The typed-accessor path cannot follow without duplicating ~20
accessor variants; it would stay as-is.

### B2. Pre-size the output buffer

`JSONWriter.ensureCapacity(int)` is public. Nothing calls it, so a large message grows the buffer
by repeated `grow()`-and-copy from fastjson2's initial thread-cached array. A per-Descriptor
rolling estimate (an EMA of that type's recent output sizes) passed to `ensureCapacity` at the top
of `writeMessage` would collapse that chain to one allocation. Cheap to build, and it targets
exactly the benchmarks that are slowest in absolute terms (`RepeatedAndMap`, `stringHeavy`, deep
nesting).

### B3. Fuse constant name+value pairs

`writeNameRaw` handles the leading comma, so anything constant can be folded into the "name":

- implicit-presence `bool` — the value written is always `true`, so `"active":true` is one
  constant and one call;
- enums — the value set is known at code-generation time, so `"status":"ACTIVE"` can be a
  `byte[][]`/`char[][]` indexed by enum number. This also removes fastjson2's escape scan and
  UTF-16→UTF-8 transcoding of the enum name on every write, which is pure waste for names that
  are ASCII and fixed.

Cost: constants grow as fields × enum cardinality.

### B4. Zero-allocation numeric map keys

`entry.getKey().toString()` allocates a `String` per entry for int/long-keyed maps.
`writeNameRaw(byte[], int, int)` and `writeNameRaw(char[], int, int)` both exist, so the key can
be formatted into a reusable scratch buffer as `"`, digits, `"`, `:` and written in one call with
comma handling intact. (`writeName(int)`/`writeName(long)` look like the answer but are not — they
write an *unquoted* number and no colon, which proto3 JSON map keys forbid.)

### B5. Zero-allocation `Timestamp` / `Duration`

`writeTimestampDirect` allocates an exact-size `byte[20..30]` per timestamp purely because
`writeStringLatin1` has no offset/length overload. `writeRaw(char[], int, int)` does take one — if
it behaves identically on UTF-8 and UTF-16 writers (needs checking), the formatting could target a
thread-local scratch with the quotes written in, and the allocation disappears. Timestamps are
common enough in real payloads for this to matter.

### B6. Base64 without the `ByteString` copy

`writeBase64(v.toByteArray())` copies the entire payload before encoding it. Encoding straight out
of the `ByteString` (`asReadOnlyByteBuffer()`, or `copyTo` into a pooled buffer) removes a
payload-sized allocation per bytes field — the largest single allocation in any message carrying a
blob.

### B7. Resolve WKT-ness at schema-build time everywhere

`WellKnownTypes.isWellKnownType()` is a `Set.of(…).contains(descriptor.getFullName())` probe, and
`WellKnownTypes.write()` then re-dispatches on a `String` switch — both **per nested WKT write**,
in the typed and reflection paths. A message field's type is known when the schema is built, so a
`WktKind` stored in the accessor / `FieldInfo` turns both into a tableswitch. Codegen already
bypasses this for Timestamp, Duration and the wrappers.

### B8. Memoize the nested encoder in typed accessors

`PresenceMessageAccessor` / `RepeatedMessageAccessor` call `writer.writeMessage`, re-entering the
three-tier dispatch — an `instanceof BuffJsonCodecHolder` plus a megamorphic `buffJsonEncoder()`,
or a `TypedMessageSchema` ConcurrentHashMap lookup — for every nested message. A message field's
concrete type is fixed, so the accessor can cache the resolved encoder on first use. This is the
typed-path analogue of codegen's direct `INSTANCE.writeFields` call, which is where a good part of
the codegen-vs-typed gap on nested messages comes from.

### B9. Hoist the type switch out of reflection-path loops

`FieldWriter.writeRepeated`/`writeMap` call `writeValue`, which re-switches on `JavaType` for
every element. Specializing per element type (as the typed accessors now do) would help
`DynamicMessage` and `Any` payloads, which are the only traffic left on that path.

### B10. Small allocation cleanups

- `writeUnsignedLongString` allocates `byte[20]` then `Arrays.copyOf` for values ≥ 2^63 — compute
  the digit count first and allocate once (the same trick `writeDurationDirect` already uses).
- `writeFieldMask` allocates a `StringBuilder` + `String`, plus one `String` per path in
  `snakeToCamel`.

### B11. Encoder-level odds and ends

- `messageWriter()` reads a `volatile` per encode. Marginal on x86, an acquire barrier on ARM.
  Constructing the writer eagerly (and rebuilding it in the setters) would make the field final.
- `encode()`/`encodeToBytes()` copy out of the pooled buffer to build the `String`/`byte[]`. That
  is inherent to the API shape; a streaming/`Appendable` API (already on the "Not Yet Implemented"
  list) is what lets hot callers avoid it.

## Things that looked promising and are not

- **`writeName(int)` / `writeName(long)`** for numeric map keys — writes an unquoted number and no
  colon. See B4.
- **`writeInt32(int[])` / `writeInt64(long[])` / `writeDouble(double[])`** write a whole JSON array
  in one call, but protobuf's `Internal.*List` does not expose its backing array, so feeding them
  would require copying it out — which costs more than the loop saves. (`writeString(List<String>)`
  is the exception, since it takes the `List` directly — see applied item 3.)
- **`writeDouble(double[])` for repeated doubles**, even if the array were free: fastjson2 writes
  non-finite values as `null`, whereas proto3 JSON requires `"NaN"`/`"Infinity"` strings, so each
  element needs its own finite check anyway.

