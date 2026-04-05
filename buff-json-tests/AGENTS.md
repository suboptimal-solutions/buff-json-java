# AGENTS.md - buff-json-tests

## Module Purpose

Conformance tests verifying that `BuffJson.encode()` produces output identical to
`JsonFormat.printer().omittingInsignificantWhitespace().print()` for all proto3 JSON features.

## Test Structure

- `BuffJsonReferenceTest.java` — 3 smoke tests (scalar, default, complex messages)
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

## Test Pattern

Every `assertMatchesReference()` validates **both paths** (codegen and runtime):

```java
String expected = JsonFormat.printer().omittingInsignificantWhitespace().print(message);
String codegen = CODEGEN_ENCODER.encode(message);                  // uses generated encoders
String runtime = RUNTIME_ENCODER.encode(message);                  // setGeneratedEncoders(false)
assertEquals(expected, codegen, "Codegen mismatch for " + type);
assertEquals(expected, runtime, "Runtime mismatch for " + type);
```

Any tests use the same dual-path pattern with `BuffJsonEncoder.setTypeRegistry()`.

## Protoc Plugin Integration

The module configures the `buff-json-protoc-plugin` via `<jvmPlugin>` in the
protobuf-maven-plugin. Generated `*JsonEncoder` classes and the `META-INF/services` file
are produced alongside standard protobuf sources. A `<resources>` entry copies the
`META-INF` directory from generated-sources to `target/classes`.

## Proto Files

- `conformance_test.proto` — comprehensive proto3 test messages including TestAny, TestEmpty
- `validate_test.proto` — test messages with buf.validate annotations and field comments

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

