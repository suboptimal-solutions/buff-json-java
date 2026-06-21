# CLAUDE.md - buff-json-conformance

## Module Purpose

Testee for the **official protobuf conformance test suite**
([`conformance_test_runner`](https://github.com/protocolbuffers/protobuf/tree/main/conformance)).
Validates buff-json's proto3 JSON encode/decode against Google's canonical spec test corpus
(the same suite protobuf-java, Go, C++, etc. run against themselves).

buff-json is a proto3 JSON codec, so the testee deliberately covers **only the JSON portion** of
the suite. The protobuf binary side of a JSON test is handled by protobuf-java; binary↔binary
round-trips (which would only exercise protobuf-java's wire codec) are skipped.

## How It Works

The runner spawns the testee and drives it over stdin/stdout with a framed protocol: a
little-endian `uint32` length prefix followed by a serialized `ConformanceRequest`; the testee
replies with a length-prefixed `ConformanceResponse`. The loop runs until the runner closes stdin.

`ConformanceTestee.handle(request)`:

1. `message_type == "conformance.FailureSet"` (the runner's first probe) → reply with an empty
   `FailureSet`. We declare no internal failures and rely on the external `failure_list.txt`.
2. `message_type != protobuf_test_messages.proto3.TestAllTypesProto3` (proto2 / editions) → `skipped`.
3. Neither input nor output is JSON (a pure binary round-trip) → `skipped`.
4. Parse input — `json_payload` via `BuffJson.decoder()`, `protobuf_payload` via protobuf-java.
   `text_payload` / `jspb_payload` → `skipped`. Parse failures → `parse_error`.
5. Serialize to the requested format — `JSON` via `BuffJson.encoder()`, `PROTOBUF` via
   protobuf-java. `TEXT_FORMAT` / `JSPB` → `skipped`. Serialize failures → `serialize_error`.

`Any` fields are resolved via a `TypeRegistry` registering `TestAllTypesProto3` plus the
well-known types that conformance packs into `Any`.

**Only `stderr` is used for diagnostics** — anything on `stdout` other than framed responses
corrupts the protocol. Both `parse`/`serialize` are wrapped in `catch (Throwable)` so a single bad
input (including stack-overflow on the deep-recursion cases) is reported as an error rather than
killing the long-lived process.

## Key Files

- `src/main/java/.../ConformanceTestee.java` — the testee main + `handle()` dispatch
- `src/main/protobuf/conformance.proto` — vendored from protobuf `v34.1` (the runner protocol)
- `src/main/protobuf/google/protobuf/test_messages_proto3.proto` — vendored from protobuf `v34.1`
- `failure_list.txt` — tests buff-json is expected to fail (empty until curated from a CI run)
- `test-conformance.sh` — runs the runner against the shaded testee jar

## Running Locally

```bash
# 1. Build the shaded testee jar (target/conformance-testee.jar)
mvn -pl buff-json-conformance -am package -DskipTests

# 2. Build conformance_test_runner from protobuf source (matching protobuf-java 4.34.1 -> v34.1)
git clone --depth 1 -b v34.1 --recurse-submodules https://github.com/protocolbuffers/protobuf
cmake -S protobuf -B run -Dprotobuf_BUILD_CONFORMANCE=ON -Dprotobuf_BUILD_TESTS=OFF -Dprotobuf_BUILD_LIBUPB=OFF
cmake --build run --parallel --target conformance_test_runner

# 3. Run the proto3 JSON suite (codegen path, the default)
CONF_TEST_PATH=run/conformance_test_runner ./buff-json-conformance/test-conformance.sh

# ...or exercise a specific buff-json path:
CONF_TEST_PATH=run/conformance_test_runner BUFFJSON_PATH=runtime    ./buff-json-conformance/test-conformance.sh
CONF_TEST_PATH=run/conformance_test_runner BUFFJSON_PATH=reflection ./buff-json-conformance/test-conformance.sh
```

`BUFFJSON_PATH` (default `codegen`) selects which buff-json path the testee exercises:

|    value     |       encode path        |       decode path        |
|--------------|--------------------------|--------------------------|
| `codegen`    | generated `*JsonEncoder` | generated `*JsonDecoder` |
| `runtime`    | typed-accessor           | reflection               |
| `reflection` | pure reflection          | reflection               |

`ENFORCE_CONFORMANCE=1` makes `test-conformance.sh` propagate the runner's exit code (fail on
unexpected results); the default `0` reports results without failing.

## CI

The `conformance` job in `.github/workflows/ci.yml` runs the suite **on a single OS**
(`ubuntu-latest`), once **per buff-json path** — `codegen`, `runtime`, and `reflection` — so all
three encode/decode paths are validated against the official corpus. It builds the testee jar once,
builds + caches `conformance_test_runner` (keyed on `PROTOBUF_CONFORMANCE_VERSION`), then loops
`BUFFJSON_PATH` over the three values invoking `test-conformance.sh` in report-only mode
(`ENFORCE_CONFORMANCE=0`). To lock conformance in: review the CI output, add the genuinely
unsupported test names to `failure_list.txt`, and flip the job to `ENFORCE_CONFORMANCE=1`.

## Curating the Failure List

Run the suite (locally or in CI). For each `Required.Proto3.*`/`Recommended.Proto3.*` test that
fails and is a genuine buff-json limitation, add its full name to `failure_list.txt`. A listed test
that later *passes* is reported as an unexpected success, so prune entries as bugs are fixed.

## Build Notes

- **Not published** (`maven.install.skip`/`maven.deploy.skip` = true; excluded from the release profile).
- Runs `buff-json-protoc-plugin` on the vendored protos (codegen path is the one under test).
- Compiler `-Werror` is relaxed to plain `-Xlint:all,-processing`: the generated protobuf code for
  `TestAllTypesProto3` is not under our control and trips warnings.
- `maven-shade-plugin` builds `target/conformance-testee.jar` (Main-Class `ConformanceTestee`).
- The vendored protos are kept **verbatim** from protobuf `v34.1`, including
  `java_multiple_files = false` (so `TestAllTypesProto3` nests under outer class `TestMessagesProto3`).
  Bump them together with `protobuf.version` / `PROTOBUF_CONFORMANCE_VERSION`.

## Dependencies

- `buff-json` — the codec under test
- `com.google.protobuf:protobuf-java` — protocol + test message types, binary I/O
- `com.alibaba.fastjson2:fastjson2` — transitive JSON engine (bundled into the shaded jar)

