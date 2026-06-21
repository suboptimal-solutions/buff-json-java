# CLAUDE.md - buff-json-tests

## Module Purpose

Conformance tests verifying that `BuffJson.encode()` produces output identical to
`JsonFormat.printer().omittingInsignificantWhitespace().print()` for all proto3 JSON
features, across all three encoding paths (codegen, typed-accessor runtime,
pure reflection).

## Test Structure

- `BuffJsonReferenceTest.java` — 5 smoke tests (scalar, default, complex, plus two `DynamicMessage` tests on UTF-16 and UTF-8 paths — `DynamicMessage` is the only thing that exclusively exercises pure reflection in production)
- `BuffJsonMemoryTest.java` — 8 reachability tests using `WeakReference` + `System.gc()` to confirm the encoder doesn't retain `Message` references after `encode`/`encodeToBytes`/`encode(stream)` on any of the three paths, including `DynamicMessage`. Steady-state allocation regressions are caught separately by `./allocation-check.sh` in CI (JMH `-prof gc`).
- `BuffJsonCrossPathFuzzTest.java` — seeded-random (reproducible) fuzzer over `TestAllTypesProto3`. `encodePathsAgreeAndAreParseable` asserts **codegen == typed == reflection** byte-for-byte (UTF-16 and UTF-8) over 500 messages — the direct "the three paths agree" guarantee — plus a buff-json self round-trip. `decodePathsRoundTrip` asserts both decode paths reconstruct messages from `JsonFormat`-printed JSON. (It does not byte-compare encode output against `JsonFormat` because fastjson2 and protobuf may format the same float/double differently — both round-trip to the same value; curated byte-equality lives in `BuffJsonProto3ConformanceTest`.)
- `BuffJsonProto3ConformanceTest.java` — 81 tests in 16 nested classes:
  - ScalarTypes (13): all types, boundaries, NaN, Infinity, -0.0, unicode, escapes, bytes
  - RepeatedFields (3): all scalar types, empty, single element
  - Enums (3): values, default omission, repeated
  - NestedMessages (4): nested, repeated, empty, recursive (3 levels)
  - OneofFields (7): int/string/bool/message/enum, not set, default with presence
  - MapFields (5): string/int/bool keys, all value types, empty
  - TimestampTests (5): basic, nanos, full nanos, epoch, pre-epoch
  - DurationTests (4): basic, nanos, negative, zero
  - FieldMaskTests (2): camelCase path joining, empty
  - StructTests (8): struct, nested struct, all Value kinds, list, empty
  - WrapperTests (11): all 9 types, zero with presence, all combined
  - ExplicitPresence (3): set, set-to-default, not set
  - CustomJsonName (1): json_name annotation
  - AnyTests (6): regular message, Duration, Timestamp, nested Any, empty, default inner
  - EmptyTests (1): google.protobuf.Empty serialization
  - EmptyMessages (5): all message types empty
  - ExternalMessages: google.type.* messages (Color, Money, Interval, PostalAddress)
  - OfficialProto3TestMessages: the official `protobuf_test_messages.proto3.TestAllTypesProto3` sample (the same message the conformance_test_runner drives), compared **byte-exact** against `JsonFormat` across all three paths. Covers edge cases the project's own protos don't: multiple distinct enum types per message, a negative enum value (NEG = -1 → name "NEG"), an aliased enum (allow_alias → canonical first name), digit/underscore field names, an interleaved oneof (emitted in field-number order), and a standalone `google.protobuf.NullValue` oneof field (serialized as JSON `null`).

`BuffJsonProto3DecodeConformanceTest.java` mirrors the encode tests for the JSON→protobuf direction (`assertDecodeMatchesOriginal`: JsonFormat.print → BuffJson.decode → equals), including the same `OfficialProto3TestMessages` sample (a JSON `null` round-trips back to a present `NullValue` field).

## Test Pattern

Every `assertMatchesReference()` validates **all three paths** (codegen, typed-accessor, pure reflection) against the `JsonFormat.printer()` reference:

```java
private static final BuffJsonEncoder CODEGEN_ENCODER    = BuffJson.encoder().setGeneratedEncoders(true);
private static final BuffJsonEncoder TYPED_ENCODER      = BuffJson.encoder().setGeneratedEncoders(false);
private static final BuffJsonEncoder REFLECTION_ENCODER = BuffJson.encoder().setGeneratedEncoders(false)
                                                                  .setTypedAccessors(false);

String expected = JsonFormat.printer().omittingInsignificantWhitespace().print(message);
assertEquals(expected, CODEGEN_ENCODER.encode(message),    "Codegen mismatch for " + type);
assertEquals(expected, TYPED_ENCODER.encode(message),      "Typed-accessor mismatch for " + type);
assertEquals(expected, REFLECTION_ENCODER.encode(message), "Reflection mismatch for " + type);
```

`AnyTests` uses three independent encoders configured with `TypeRegistry` (don't reuse one builder — `setGeneratedEncoders` returns `this` and mutates state, so chaining off a shared instance aliases configurations).

## Protoc Plugin Integration

The module configures the `buff-json-protoc-plugin` via `<jvmPlugin>` in the
protobuf-maven-plugin. Generated `*JsonEncoder` classes and the `META-INF/services` file
are produced alongside standard protobuf sources. A `<resources>` entry copies the
`META-INF` directory from generated-sources to `target/classes`.

## Proto Files

- `conformance_test.proto` — comprehensive proto3 test messages including TestAny, TestEmpty
- `validate_test.proto` — test messages with buf.validate annotations and field comments
- `google/protobuf/test_messages_proto3.proto` — the official protobuf conformance sample message (`TestAllTypesProto3`), vendored verbatim from protobuf `v34.1` (same file as in `buff-json-conformance`). Bump together with `protobuf.version`.

### JSON Schema Tests

- `BuffJsonSchemaTest.java` — 16 tests covering `ProtobufSchema.generate()`:
  - allScalarTypes: all 15 scalar types mapped to correct JSON Schema types (including format/contentEncoding)
  - repeatedScalars: repeated fields → array with items
  - nestedMessages: nested objects with title, repeated nested, enums with title
  - recursiveMessages: $defs/$ref for self-referential types
  - oneofFields: all oneof variants in properties
  - mapFields: string/int/message map values → additionalProperties
  - wrapperTypes: all 9 wrapper types unwrapped (including format on int64/uint64, contentEncoding on bytes)
  - timestampType: Timestamp → string with date-time format + description
  - durationType: Duration → string with description
  - fieldMaskType: FieldMask → string with description
  - structValueListValue: Struct/Value/ListValue → object/any/array with descriptions
  - anyType: Any → object with required @type + description
  - emptyType: Empty → object
  - customJsonName: json_name annotation used as property key
  - generateFromClass: Class-based API convenience method
  - protoCommentsFromGeneratedRegistry: verifies proto comments from generated `*Comments` classes appear as `description` (messages, enums, no-comment → null)

### buf.validate Constraint Tests

- `BuffJsonSchemaValidateTest.java` — 30 tests in three groups:
  - **Constraint mapping** (18 tests): string min/max length, email/hostname/uri/uuid/ipv4/ipv6 format, pattern, const, in/enum, int32 inclusive/exclusive ranges, uint32 minimum, int64 constraints in description, required array, repeated min/max/unique items, map min/max properties, enum in constraint
  - **Float/double handling** (3 tests): constrained double → constraints on number branch of oneOf, finite float → collapsed to plain number, unconstrained double → standard oneOf preserved
  - **Comment coexistence** (9 tests): proto field comments + string constraints, format, pattern, numeric constraints, int64 description append, required + comment, repeated + comment, map + comment, enum + comment

## Dependencies

- `buff-json` — the library under test
- `buff-json-schema` — JSON Schema generation under test
- `build.buf:protovalidate` — buf.validate constraint types for validate_test.proto
- `protobuf-java-util` — reference `JsonFormat.printer()` for comparison
- `junit-jupiter` — test framework

