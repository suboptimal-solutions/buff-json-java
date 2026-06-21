package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import com.google.protobuf.*;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf_test_messages.proto3.TestMessagesProto3.ForeignEnum;
import com.google.protobuf_test_messages.proto3.TestMessagesProto3.TestAllTypesProto3;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.proto.*;

class BuffJsonProto3ConformanceTest {

	private static final JsonFormat.Printer REFERENCE = JsonFormat.printer().omittingInsignificantWhitespace();
	private static final BuffJsonEncoder CODEGEN_ENCODER = BuffJson.encoder().setGeneratedEncoders(true);
	private static final BuffJsonEncoder TYPED_ENCODER = BuffJson.encoder().setGeneratedEncoders(false);
	private static final BuffJsonEncoder REFLECTION_ENCODER = BuffJson.encoder().setGeneratedEncoders(false)
			.setTypedAccessors(false);

	private void assertMatchesReference(Message message) throws Exception {
		String expected = REFERENCE.print(message);
		String typeName = message.getDescriptorForType().getFullName();

		// UTF-16 path (encode → String)
		assertEquals(expected, CODEGEN_ENCODER.encode(message), "Codegen mismatch for " + typeName);
		assertEquals(expected, TYPED_ENCODER.encode(message), "Typed-accessor mismatch for " + typeName);
		assertEquals(expected, REFLECTION_ENCODER.encode(message), "Reflection mismatch for " + typeName);

		// UTF-8 path (encodeToBytes → byte[])
		assertEquals(expected, new String(CODEGEN_ENCODER.encodeToBytes(message), StandardCharsets.UTF_8),
				"Codegen UTF-8 mismatch for " + typeName);
		assertEquals(expected, new String(TYPED_ENCODER.encodeToBytes(message), StandardCharsets.UTF_8),
				"Typed-accessor UTF-8 mismatch for " + typeName);
		assertEquals(expected, new String(REFLECTION_ENCODER.encodeToBytes(message), StandardCharsets.UTF_8),
				"Reflection UTF-8 mismatch for " + typeName);

		// OutputStream path (encode to OutputStream — uses same UTF-8 writer, verifies
		// flushTo)
		var out = new ByteArrayOutputStream();
		REFLECTION_ENCODER.encode(message, out);
		assertEquals(expected, out.toString(StandardCharsets.UTF_8),
				"Reflection OutputStream mismatch for " + typeName);
	}

	// =========================================================================
	// Scalar types
	// =========================================================================
	@Nested
	class ScalarTypes {

		@Test
		void allScalarTypes() throws Exception {
			assertMatchesReference(TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalInt64(123456789012345L)
					.setOptionalUint32(100).setOptionalUint64(999999999999L).setOptionalSint32(-42)
					.setOptionalSint64(-123456789012345L).setOptionalFixed32(100).setOptionalFixed64(999999999999L)
					.setOptionalSfixed32(-100).setOptionalSfixed64(-999999999999L).setOptionalFloat(3.14f)
					.setOptionalDouble(2.718281828).setOptionalBool(true).setOptionalString("hello world")
					.setOptionalBytes(ByteString.copyFromUtf8("binary data")).build());
		}

		@Test
		void defaultValuesOmitted() throws Exception {
			assertMatchesReference(TestAllScalars.getDefaultInstance());
		}

		@Test
		void integerBoundaries() throws Exception {
			assertMatchesReference(TestAllScalars.newBuilder().setOptionalInt32(Integer.MAX_VALUE)
					.setOptionalInt64(Long.MAX_VALUE).setOptionalUint32(-1) // unsigned max = 4294967295
					.setOptionalUint64(-1L) // unsigned max
					.setOptionalSint32(Integer.MIN_VALUE).setOptionalSint64(Long.MIN_VALUE).build());
		}

		@Test
		void negativeNumbers() throws Exception {
			assertMatchesReference(TestAllScalars.newBuilder().setOptionalInt32(-1).setOptionalInt64(-1L)
					.setOptionalSint32(-1).setOptionalSint64(-1L).setOptionalSfixed32(-1).setOptionalSfixed64(-1L)
					.setOptionalFloat(-3.14f).setOptionalDouble(-2.718).build());
		}

		@Test
		void specialFloatValues() throws Exception {
			assertMatchesReference(
					TestAllScalars.newBuilder().setOptionalFloat(Float.NaN).setOptionalDouble(Double.NaN).build());
		}

		@Test
		void infinityValues() throws Exception {
			assertMatchesReference(TestAllScalars.newBuilder().setOptionalFloat(Float.POSITIVE_INFINITY)
					.setOptionalDouble(Double.NEGATIVE_INFINITY).build());
		}

		@Test
		void negativeZero() throws Exception {
			assertMatchesReference(TestAllScalars.newBuilder().setOptionalFloat(-0.0f).setOptionalDouble(-0.0).build());
		}

		@Test
		void verySmallFloat() throws Exception {
			assertMatchesReference(TestAllScalars.newBuilder().setOptionalFloat(Float.MIN_VALUE)
					.setOptionalDouble(Double.MIN_VALUE).build());
		}

		@Test
		void unicodeStrings() throws Exception {
			assertMatchesReference(TestAllScalars.newBuilder().setOptionalString("Hello \u4e16\u754c") // Chinese
					.build());
		}

		@Test
		void stringWithEscapeChars() throws Exception {
			assertMatchesReference(
					TestAllScalars.newBuilder().setOptionalString("line1\nline2\ttab\\backslash\"quote").build());
		}

		@Test
		void emptyString() throws Exception {
			// Empty string is default, should be omitted in proto3
			assertMatchesReference(TestAllScalars.newBuilder().setOptionalString("").build());
		}

		@Test
		void emptyBytes() throws Exception {
			assertMatchesReference(TestAllScalars.newBuilder().setOptionalBytes(ByteString.EMPTY).build());
		}

		@Test
		void bytesWithPaddingEdgeCases() throws Exception {
			// 1 byte -> 2 base64 chars + padding
			assertMatchesReference(
					TestAllScalars.newBuilder().setOptionalBytes(ByteString.copyFrom(new byte[]{0x01})).build());
			// 2 bytes -> 3 base64 chars + padding
			assertMatchesReference(
					TestAllScalars.newBuilder().setOptionalBytes(ByteString.copyFrom(new byte[]{0x01, 0x02})).build());
			// 3 bytes -> 4 base64 chars, no padding
			assertMatchesReference(TestAllScalars.newBuilder()
					.setOptionalBytes(ByteString.copyFrom(new byte[]{0x01, 0x02, 0x03})).build());
		}
	}

	// =========================================================================
	// Repeated fields
	// =========================================================================
	@Nested
	class RepeatedFields {

		@Test
		void repeatedScalars() throws Exception {
			assertMatchesReference(TestRepeatedScalars.newBuilder().addRepeatedInt32(1).addRepeatedInt32(2)
					.addRepeatedInt32(3).addRepeatedInt64(100L).addRepeatedInt64(200L).addRepeatedUint32(10)
					.addRepeatedUint32(20).addRepeatedUint64(1000L).addRepeatedSint32(-1).addRepeatedSint32(1)
					.addRepeatedSint64(-100L).addRepeatedSint64(100L).addRepeatedFixed32(42).addRepeatedFixed64(42L)
					.addRepeatedSfixed32(-42).addRepeatedSfixed64(-42L).addRepeatedFloat(1.1f).addRepeatedFloat(2.2f)
					.addRepeatedDouble(1.1).addRepeatedDouble(2.2).addRepeatedBool(true).addRepeatedBool(false)
					.addRepeatedString("a").addRepeatedString("b").addRepeatedBytes(ByteString.copyFromUtf8("x"))
					.build());
		}

		@Test
		void emptyRepeatedFieldsOmitted() throws Exception {
			assertMatchesReference(TestRepeatedScalars.getDefaultInstance());
		}

		@Test
		void singleElementRepeated() throws Exception {
			assertMatchesReference(TestRepeatedScalars.newBuilder().addRepeatedInt32(42).build());
		}
	}

	// =========================================================================
	// Enums
	// =========================================================================
	@Nested
	class Enums {

		@Test
		void enumValues() throws Exception {
			assertMatchesReference(TestNesting.newBuilder().setEnumValue(TestEnum.TEST_ENUM_FOO).build());
		}

		@Test
		void defaultEnumOmitted() throws Exception {
			assertMatchesReference(TestNesting.newBuilder().setEnumValue(TestEnum.TEST_ENUM_UNSPECIFIED).build());
		}

		@Test
		void repeatedEnums() throws Exception {
			assertMatchesReference(TestNesting.newBuilder().addRepeatedEnum(TestEnum.TEST_ENUM_FOO)
					.addRepeatedEnum(TestEnum.TEST_ENUM_BAR).addRepeatedEnum(TestEnum.TEST_ENUM_BAZ).build());
		}

		@Test
		void unrecognizedEnumValue() throws Exception {
			// Set enum field to a value not defined in TestEnum (e.g. 999)
			// Proto3 JSON spec: unrecognized enum values are written as integers
			assertMatchesReference(TestNesting.newBuilder().setEnumValueValue(999).build());
		}

		@Test
		void unrecognizedEnumInOneof() throws Exception {
			assertMatchesReference(TestOneof.newBuilder().setEnumValueValue(999).build());
		}

		@Test
		void unrecognizedEnumInRepeated() throws Exception {
			// Mix recognized and unrecognized values
			TestNesting msg = TestNesting.newBuilder().addRepeatedEnumValue(1) // TEST_ENUM_FOO
					.addRepeatedEnumValue(999) // unrecognized
					.addRepeatedEnumValue(2) // TEST_ENUM_BAR
					.build();
			assertMatchesReference(msg);
		}

		@Test
		void unrecognizedEnumWithExplicitPresence() throws Exception {
			// optional enum field with unrecognized value — should serialize with presence
			assertMatchesReference(TestOptionalFields.newBuilder().setOptionalEnumValue(999).build());
		}
	}

	// =========================================================================
	// Nested messages
	// =========================================================================
	@Nested
	class NestedMessages {

		@Test
		void nestedMessage() throws Exception {
			assertMatchesReference(TestNesting.newBuilder()
					.setNested(NestedMessage.newBuilder().setValue(42).setName("test")).build());
		}

		@Test
		void repeatedNestedMessages() throws Exception {
			assertMatchesReference(
					TestNesting.newBuilder().addRepeatedNested(NestedMessage.newBuilder().setValue(1).setName("a"))
							.addRepeatedNested(NestedMessage.newBuilder().setValue(2).setName("b")).build());
		}

		@Test
		void emptyNestedMessage() throws Exception {
			assertMatchesReference(TestNesting.newBuilder().setNested(NestedMessage.getDefaultInstance()).build());
		}

		@Test
		void recursiveMessage() throws Exception {
			assertMatchesReference(TestRecursive.newBuilder().setValue(1)
					.setChild(TestRecursive.newBuilder().setValue(2).setChild(TestRecursive.newBuilder().setValue(3)))
					.build());
		}
	}

	// =========================================================================
	// Oneof
	// =========================================================================
	@Nested
	class OneofFields {

		@Test
		void oneofInt() throws Exception {
			assertMatchesReference(TestOneof.newBuilder().setName("test").setIntValue(42).build());
		}

		@Test
		void oneofString() throws Exception {
			assertMatchesReference(TestOneof.newBuilder().setStringValue("hello").build());
		}

		@Test
		void oneofBool() throws Exception {
			assertMatchesReference(TestOneof.newBuilder().setBoolValue(true).build());
		}

		@Test
		void oneofMessage() throws Exception {
			assertMatchesReference(
					TestOneof.newBuilder().setMessageValue(NestedMessage.newBuilder().setValue(42)).build());
		}

		@Test
		void oneofEnum() throws Exception {
			assertMatchesReference(TestOneof.newBuilder().setEnumValue(TestEnum.TEST_ENUM_BAR).build());
		}

		@Test
		void oneofNotSet() throws Exception {
			assertMatchesReference(TestOneof.newBuilder().setName("no oneof").build());
		}

		@Test
		void oneofWithDefaultValue() throws Exception {
			// Oneof int with value 0 should still be serialized (oneof has presence)
			assertMatchesReference(TestOneof.newBuilder().setIntValue(0).build());
		}
	}

	// =========================================================================
	// Map fields
	// =========================================================================
	@Nested
	class MapFields {

		@Test
		void stringKeyMaps() throws Exception {
			assertMatchesReference(TestMaps.newBuilder().putStringToString("key1", "value1")
					.putStringToString("key2", "value2").putStringToInt32("count", 42)
					.putStringToInt64("big", 999999999999L).putStringToFloat("pi", 3.14f).putStringToDouble("e", 2.718)
					.putStringToBool("flag", true).putStringToBytes("data", ByteString.copyFromUtf8("binary"))
					.putStringToMessage("msg", NestedMessage.newBuilder().setValue(1).build())
					.putStringToEnum("status", TestEnum.TEST_ENUM_FOO).build());
		}

		@Test
		void intKeyMaps() throws Exception {
			assertMatchesReference(TestMaps.newBuilder().putInt32ToString(1, "one").putInt32ToString(2, "two")
					.putInt64ToString(100L, "hundred").putUint32ToString(42, "answer").putUint64ToString(999L, "big")
					.putSint32ToString(-1, "negative").putSint64ToString(-100L, "neg hundred")
					.putFixed32ToString(10, "fixed").putFixed64ToString(20L, "fixed64")
					.putSfixed32ToString(-10, "sfixed").putSfixed64ToString(-20L, "sfixed64").build());
		}

		@Test
		void boolKeyMap() throws Exception {
			assertMatchesReference(
					TestMaps.newBuilder().putBoolToString(true, "yes").putBoolToString(false, "no").build());
		}

		@Test
		void emptyMapsOmitted() throws Exception {
			assertMatchesReference(TestMaps.getDefaultInstance());
		}

		@Test
		void singleEntryMap() throws Exception {
			assertMatchesReference(TestMaps.newBuilder().putStringToString("only", "one").build());
		}
	}

	// =========================================================================
	// Well-known types: Timestamp
	// =========================================================================
	@Nested
	class TimestampTests {

		@Test
		void timestamp() throws Exception {
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1711627200).setNanos(0)).build());
		}

		@Test
		void timestampWithNanos() throws Exception {
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1711627200).setNanos(123456000)).build());
		}

		@Test
		void timestampWithFullNanos() throws Exception {
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1711627200).setNanos(123456789)).build());
		}

		@Test
		void timestampEpoch() throws Exception {
			assertMatchesReference(
					TestTimestamp.newBuilder().setValue(Timestamp.newBuilder().setSeconds(0).setNanos(0)).build());
		}

		@Test
		void timestampBeforeEpoch() throws Exception {
			assertMatchesReference(
					TestTimestamp.newBuilder().setValue(Timestamp.newBuilder().setSeconds(-1).setNanos(0)).build());
		}

		@Test
		void timestampMillisOnly() throws Exception {
			// Nanos divisible by 1_000_000 → 3 fractional digits
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1711627200).setNanos(1_000_000)).build());
		}

		@Test
		void timestampMicrosOnly() throws Exception {
			// Nanos divisible by 1_000 → 6 fractional digits
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1711627200).setNanos(1_000)).build());
		}

		@Test
		void timestampSingleNano() throws Exception {
			// 1 nano → 9 fractional digits
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1711627200).setNanos(1)).build());
		}

		@Test
		void timestampMaxNanos() throws Exception {
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1711627200).setNanos(999_999_999)).build());
		}

		@Test
		void timestampYear2000() throws Exception {
			// 2000-01-01T00:00:00Z = 946684800
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(946684800).setNanos(0)).build());
		}

		@Test
		void timestampYear1970() throws Exception {
			// 1970-01-01T00:00:01Z
			assertMatchesReference(
					TestTimestamp.newBuilder().setValue(Timestamp.newBuilder().setSeconds(1).setNanos(0)).build());
		}

		@Test
		void timestampFarFuture() throws Exception {
			// 9999-12-31T23:59:59Z = 253402300799
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(253402300799L).setNanos(0)).build());
		}

		@Test
		void timestampFarPast() throws Exception {
			// 0001-01-01T00:00:00Z = -62135596800
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(-62135596800L).setNanos(0)).build());
		}

		@Test
		void timestampWithNanosBeforeEpoch() throws Exception {
			// Negative seconds with nanos
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(-1711627200).setNanos(500_000_000)).build());
		}

		@Test
		void timestampMidnight() throws Exception {
			// 2024-03-28T00:00:00.000Z — all time components zero
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1711584000).setNanos(0)).build());
		}

		@Test
		void timestampEndOfDay() throws Exception {
			// 2024-03-28T23:59:59.999999999Z
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1711670399).setNanos(999_999_999)).build());
		}

		@Test
		void timestampLeapYear() throws Exception {
			// 2024-02-29T12:00:00Z (leap day)
			assertMatchesReference(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1709208000).setNanos(0)).build());
		}
	}

	// =========================================================================
	// Well-known types: Duration
	// =========================================================================
	@Nested
	class DurationTests {

		@Test
		void duration() throws Exception {
			assertMatchesReference(
					TestDuration.newBuilder().setValue(Duration.newBuilder().setSeconds(3600).setNanos(0)).build());
		}

		@Test
		void durationWithNanos() throws Exception {
			assertMatchesReference(TestDuration.newBuilder()
					.setValue(Duration.newBuilder().setSeconds(1).setNanos(500000000)).build());
		}

		@Test
		void durationNegative() throws Exception {
			assertMatchesReference(TestDuration.newBuilder()
					.setValue(Duration.newBuilder().setSeconds(-1).setNanos(-500000000)).build());
		}

		@Test
		void durationZero() throws Exception {
			assertMatchesReference(
					TestDuration.newBuilder().setValue(Duration.newBuilder().setSeconds(0).setNanos(0)).build());
		}
	}

	// =========================================================================
	// Well-known types: FieldMask
	// =========================================================================
	@Nested
	class FieldMaskTests {

		@Test
		void fieldMask() throws Exception {
			assertMatchesReference(TestFieldMask.newBuilder()
					.setValue(FieldMask.newBuilder().addPaths("foo").addPaths("bar_baz").addPaths("qux.quux")).build());
		}

		@Test
		void emptyFieldMask() throws Exception {
			assertMatchesReference(TestFieldMask.newBuilder().setValue(FieldMask.getDefaultInstance()).build());
		}
	}

	// =========================================================================
	// Well-known types: Struct, Value, ListValue
	// =========================================================================
	@Nested
	class StructTests {

		@Test
		void struct() throws Exception {
			assertMatchesReference(
					TestStruct.newBuilder()
							.setStructValue(Struct.newBuilder()
									.putFields("string", Value.newBuilder().setStringValue("hello").build())
									.putFields("number", Value.newBuilder().setNumberValue(42.0).build())
									.putFields("bool", Value.newBuilder().setBoolValue(true).build())
									.putFields("null", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()))
							.build());
		}

		@Test
		void structNested() throws Exception {
			assertMatchesReference(
					TestStruct.newBuilder()
							.setStructValue(Struct.newBuilder().putFields("nested",
									Value.newBuilder()
											.setStructValue(Struct.newBuilder().putFields("key",
													Value.newBuilder().setStringValue("value").build()))
											.build()))
							.build());
		}

		@Test
		void valueString() throws Exception {
			assertMatchesReference(TestStruct.newBuilder().setValue(Value.newBuilder().setStringValue("test")).build());
		}

		@Test
		void valueNumber() throws Exception {
			assertMatchesReference(TestStruct.newBuilder().setValue(Value.newBuilder().setNumberValue(3.14)).build());
		}

		@Test
		void valueBool() throws Exception {
			assertMatchesReference(TestStruct.newBuilder().setValue(Value.newBuilder().setBoolValue(false)).build());
		}

		@Test
		void valueNull() throws Exception {
			assertMatchesReference(
					TestStruct.newBuilder().setValue(Value.newBuilder().setNullValue(NullValue.NULL_VALUE)).build());
		}

		@Test
		void listValue() throws Exception {
			assertMatchesReference(TestStruct.newBuilder()
					.setListValue(ListValue.newBuilder().addValues(Value.newBuilder().setStringValue("a"))
							.addValues(Value.newBuilder().setNumberValue(1.0))
							.addValues(Value.newBuilder().setBoolValue(true))
							.addValues(Value.newBuilder().setNullValue(NullValue.NULL_VALUE)))
					.build());
		}

		@Test
		void emptyStruct() throws Exception {
			assertMatchesReference(TestStruct.newBuilder().setStructValue(Struct.getDefaultInstance()).build());
		}
	}

	// =========================================================================
	// Well-known types: Wrappers
	// =========================================================================
	@Nested
	class WrapperTests {

		@Test
		void int32Wrapper() throws Exception {
			assertMatchesReference(TestWrappers.newBuilder().setInt32Value(Int32Value.of(42)).build());
		}

		@Test
		void uint32Wrapper() throws Exception {
			assertMatchesReference(TestWrappers.newBuilder().setUint32Value(UInt32Value.of(100)).build());
		}

		@Test
		void int64Wrapper() throws Exception {
			assertMatchesReference(TestWrappers.newBuilder().setInt64Value(Int64Value.of(123456789012345L)).build());
		}

		@Test
		void uint64Wrapper() throws Exception {
			assertMatchesReference(TestWrappers.newBuilder().setUint64Value(UInt64Value.of(999999999999L)).build());
		}

		@Test
		void floatWrapper() throws Exception {
			assertMatchesReference(TestWrappers.newBuilder().setFloatValue(FloatValue.of(3.14f)).build());
		}

		@Test
		void doubleWrapper() throws Exception {
			assertMatchesReference(TestWrappers.newBuilder().setDoubleValue(DoubleValue.of(2.718)).build());
		}

		@Test
		void boolWrapper() throws Exception {
			assertMatchesReference(TestWrappers.newBuilder().setBoolValue(BoolValue.of(true)).build());
		}

		@Test
		void stringWrapper() throws Exception {
			assertMatchesReference(TestWrappers.newBuilder().setStringValue(StringValue.of("hello")).build());
		}

		@Test
		void bytesWrapper() throws Exception {
			assertMatchesReference(
					TestWrappers.newBuilder().setBytesValue(BytesValue.of(ByteString.copyFromUtf8("binary"))).build());
		}

		@Test
		void wrapperWithZeroValue() throws Exception {
			// Zero-value wrappers should still be serialized (they have presence)
			assertMatchesReference(TestWrappers.newBuilder().setInt32Value(Int32Value.of(0))
					.setStringValue(StringValue.of("")).setBoolValue(BoolValue.of(false)).build());
		}

		@Test
		void allWrappers() throws Exception {
			assertMatchesReference(
					TestWrappers.newBuilder().setInt32Value(Int32Value.of(1)).setUint32Value(UInt32Value.of(2))
							.setInt64Value(Int64Value.of(3L)).setUint64Value(UInt64Value.of(4L))
							.setFloatValue(FloatValue.of(5.0f)).setDoubleValue(DoubleValue.of(6.0))
							.setBoolValue(BoolValue.of(true)).setStringValue(StringValue.of("eight"))
							.setBytesValue(BytesValue.of(ByteString.copyFromUtf8("nine"))).build());
		}
	}

	// =========================================================================
	// Proto3 explicit presence (optional keyword)
	// =========================================================================
	@Nested
	class ExplicitPresence {

		@Test
		void optionalFieldSet() throws Exception {
			assertMatchesReference(TestOptionalFields.newBuilder().setOptionalInt32(42).setOptionalString("present")
					.setOptionalBool(true).setOptionalEnum(TestEnum.TEST_ENUM_FOO).build());
		}

		@Test
		void optionalFieldSetToDefault() throws Exception {
			// With explicit presence, even default values should serialize
			assertMatchesReference(TestOptionalFields.newBuilder().setOptionalInt32(0).setOptionalString("")
					.setOptionalBool(false).setOptionalEnum(TestEnum.TEST_ENUM_UNSPECIFIED).build());
		}

		@Test
		void optionalFieldNotSet() throws Exception {
			// Not set at all — should be omitted
			assertMatchesReference(TestOptionalFields.getDefaultInstance());
		}
	}

	// =========================================================================
	// Custom JSON name
	// =========================================================================
	@Nested
	class CustomJsonName {

		@Test
		void customJsonName() throws Exception {
			assertMatchesReference(TestCustomJsonName.newBuilder().setValue(42).setName("test").build());
		}
	}

	// =========================================================================
	// Well-known types: Any
	// =========================================================================
	@Nested
	class AnyTests {

		private static final TypeRegistry TYPE_REGISTRY = TypeRegistry.newBuilder().add(TestAllScalars.getDescriptor())
				.add(Duration.getDescriptor()).add(Timestamp.getDescriptor()).add(TestAny.getDescriptor()).build();

		private static final JsonFormat.Printer ANY_REFERENCE = JsonFormat.printer().usingTypeRegistry(TYPE_REGISTRY)
				.omittingInsignificantWhitespace();

		private static final BuffJsonEncoder CODEGEN_ANY_ENCODER = BuffJson.encoder().setTypeRegistry(TYPE_REGISTRY);
		private static final BuffJsonEncoder TYPED_ANY_ENCODER = BuffJson.encoder().setTypeRegistry(TYPE_REGISTRY)
				.setGeneratedEncoders(false);
		private static final BuffJsonEncoder REFLECTION_ANY_ENCODER = BuffJson.encoder().setTypeRegistry(TYPE_REGISTRY)
				.setGeneratedEncoders(false).setTypedAccessors(false);

		private void assertAnyMatchesReference(Message message) throws Exception {
			String expected = ANY_REFERENCE.print(message);
			String typeName = message.getDescriptorForType().getFullName();

			// UTF-16 path
			assertEquals(expected, CODEGEN_ANY_ENCODER.encode(message), "Codegen mismatch for " + typeName);
			assertEquals(expected, TYPED_ANY_ENCODER.encode(message), "Typed-accessor mismatch for " + typeName);
			assertEquals(expected, REFLECTION_ANY_ENCODER.encode(message), "Reflection mismatch for " + typeName);

			// UTF-8 path
			assertEquals(expected, new String(CODEGEN_ANY_ENCODER.encodeToBytes(message), StandardCharsets.UTF_8),
					"Codegen UTF-8 mismatch for " + typeName);
			assertEquals(expected, new String(TYPED_ANY_ENCODER.encodeToBytes(message), StandardCharsets.UTF_8),
					"Typed-accessor UTF-8 mismatch for " + typeName);
			assertEquals(expected, new String(REFLECTION_ANY_ENCODER.encodeToBytes(message), StandardCharsets.UTF_8),
					"Reflection UTF-8 mismatch for " + typeName);

			// OutputStream path
			var out = new ByteArrayOutputStream();
			REFLECTION_ANY_ENCODER.encode(message, out);
			assertEquals(expected, out.toString(StandardCharsets.UTF_8),
					"Reflection OutputStream mismatch for " + typeName);
		}

		@Test
		void anyContainingRegularMessage() throws Exception {
			TestAllScalars inner = TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalString("hello")
					.setOptionalBool(true).build();

			assertAnyMatchesReference(TestAny.newBuilder().setValue(Any.pack(inner)).build());
		}

		@Test
		void anyContainingDuration() throws Exception {
			Duration inner = Duration.newBuilder().setSeconds(3600).setNanos(500000000).build();
			assertAnyMatchesReference(TestAny.newBuilder().setValue(Any.pack(inner)).build());
		}

		@Test
		void anyContainingTimestamp() throws Exception {
			Timestamp inner = Timestamp.newBuilder().setSeconds(1711627200).setNanos(123000000).build();
			assertAnyMatchesReference(TestAny.newBuilder().setValue(Any.pack(inner)).build());
		}

		@Test
		void anyContainingAny() throws Exception {
			TestAllScalars inner = TestAllScalars.newBuilder().setOptionalInt32(1).build();
			Any wrappedInner = Any.pack(inner);
			assertAnyMatchesReference(TestAny.newBuilder().setValue(Any.pack(wrappedInner)).build());
		}

		@Test
		void emptyAny() throws Exception {
			assertAnyMatchesReference(TestAny.getDefaultInstance());
		}

		@Test
		void anyWithDefaultInnerMessage() throws Exception {
			assertAnyMatchesReference(
					TestAny.newBuilder().setValue(Any.pack(TestAllScalars.getDefaultInstance())).build());
		}
	}

	// =========================================================================
	// Well-known types: Empty
	// =========================================================================
	@Nested
	class EmptyTests {

		@Test
		void emptyMessage() throws Exception {
			assertMatchesReference(TestEmpty.newBuilder().setValue(Empty.getDefaultInstance()).build());
		}
	}

	// =========================================================================
	// Edge cases: empty messages
	// =========================================================================
	@Nested
	class EmptyMessages {

		@Test
		void emptyTestAllScalars() throws Exception {
			assertMatchesReference(TestAllScalars.getDefaultInstance());
		}

		@Test
		void emptyTestMaps() throws Exception {
			assertMatchesReference(TestMaps.getDefaultInstance());
		}

		@Test
		void emptyTestOneof() throws Exception {
			assertMatchesReference(TestOneof.getDefaultInstance());
		}

		@Test
		void emptyTestWrappers() throws Exception {
			assertMatchesReference(TestWrappers.getDefaultInstance());
		}

		@Test
		void emptyTestNesting() throws Exception {
			assertMatchesReference(TestNesting.getDefaultInstance());
		}
	}

	// =========================================================================
	// External messages (no generated encoders — exercises runtime fallback)
	// =========================================================================
	@Nested
	class ExternalMessages {

		@Test
		void googleTypeMoney() throws Exception {
			assertMatchesReference(
					com.google.type.Money.newBuilder().setCurrencyCode("USD").setUnits(42).setNanos(990000000).build());
		}

		@Test
		void googleTypeDate() throws Exception {
			assertMatchesReference(com.google.type.Date.newBuilder().setYear(2026).setMonth(3).setDay(30).build());
		}

		@Test
		void googleTypeInterval() throws Exception {
			// Interval has nested Timestamp fields — tests that WKT handling works
			// when the outer message has no generated encoder
			assertMatchesReference(com.google.type.Interval.newBuilder()
					.setStartTime(Timestamp.newBuilder().setSeconds(1711627200).setNanos(123000000))
					.setEndTime(Timestamp.newBuilder().setSeconds(1711713600).setNanos(0)).build());
		}

		@Test
		void googleTypeLatLng() throws Exception {
			assertMatchesReference(
					com.google.type.LatLng.newBuilder().setLatitude(37.7749).setLongitude(-122.4194).build());
		}

		@Test
		void googleTypeColor() throws Exception {
			assertMatchesReference(com.google.type.Color.newBuilder().setRed(0.8f).setGreen(0.2f).setBlue(0.5f)
					.setAlpha(com.google.protobuf.FloatValue.of(1.0f)).build());
		}

		@Test
		void googleTypePostalAddress() throws Exception {
			assertMatchesReference(com.google.type.PostalAddress.newBuilder().setRegionCode("US").setPostalCode("94105")
					.setLocality("San Francisco").setAdministrativeArea("CA").addAddressLines("123 Main St")
					.addAddressLines("Suite 100").build());
		}

		@Test
		void emptyExternalMessages() throws Exception {
			assertMatchesReference(com.google.type.Money.getDefaultInstance());
			assertMatchesReference(com.google.type.Interval.getDefaultInstance());
			assertMatchesReference(com.google.type.PostalAddress.getDefaultInstance());
		}
	}

	// =========================================================================
	// Official protobuf conformance message (protobuf_test_messages.proto3.
	// TestAllTypesProto3) — the same sample the conformance_test_runner drives
	// (see buff-json-conformance). Exercises codegen edge cases the project's own
	// protos don't: multiple distinct enum types per message, a negative enum
	// value (NEG = -1), an aliased enum (allow_alias), and digit/underscore field
	// names — all compared against JsonFormat across the three paths.
	// =========================================================================
	@Nested
	class OfficialProto3TestMessages {

		@Test
		void defaultInstance() throws Exception {
			assertMatchesReference(TestAllTypesProto3.getDefaultInstance());
		}

		@Test
		void fullyPopulated() throws Exception {
			assertMatchesReference(richSample());
		}

		@Test
		void negativeNestedEnumSerializesByName() throws Exception {
			assertMatchesReference(
					TestAllTypesProto3.newBuilder().setOptionalNestedEnum(TestAllTypesProto3.NestedEnum.NEG).build());
		}

		@Test
		void aliasedEnumUsesCanonicalName() throws Exception {
			// MOO == 2 == ALIAS_BAZ; proto3 JSON emits the first-declared name.
			assertMatchesReference(
					TestAllTypesProto3.newBuilder().setOptionalAliasedEnum(TestAllTypesProto3.AliasedEnum.MOO).build());
		}

		@Test
		void multipleDistinctEnumTypes() throws Exception {
			assertMatchesReference(
					TestAllTypesProto3.newBuilder().setOptionalNestedEnum(TestAllTypesProto3.NestedEnum.BAR)
							.setOptionalForeignEnum(ForeignEnum.FOREIGN_BAZ)
							.setOptionalAliasedEnum(TestAllTypesProto3.AliasedEnum.ALIAS_BAR).build());
		}

		@Test
		void digitAndUnderscoreFieldNames() throws Exception {
			assertMatchesReference(
					TestAllTypesProto3.newBuilder().setFieldname1(1).setFieldName2(2).setFieldName3(3).setField0Name5(5)
							.setField0Name6(6).setFieldName7(7).setFIELDNAME11(11).setFIELDName12(12).build());
		}

		@Test
		void oneofUint32() throws Exception {
			assertMatchesReference(TestAllTypesProto3.newBuilder().setOneofUint32(7).build());
		}

		@Test
		void oneofString() throws Exception {
			assertMatchesReference(TestAllTypesProto3.newBuilder().setOneofString("picked").build());
		}

		@Test
		void oneofNestedMessage() throws Exception {
			assertMatchesReference(TestAllTypesProto3.newBuilder()
					.setOneofNestedMessage(TestAllTypesProto3.NestedMessage.newBuilder().setA(5)).build());
		}

		@Test
		void oneofEnum() throws Exception {
			assertMatchesReference(
					TestAllTypesProto3.newBuilder().setOneofEnum(TestAllTypesProto3.NestedEnum.NEG).build());
		}

		@Test
		void oneofNullValueSerializesAsJsonNull() throws Exception {
			// A present google.protobuf.NullValue field serializes as JSON null, not the
			// enum name "NULL_VALUE".
			assertMatchesReference(TestAllTypesProto3.newBuilder().setOneofNullValue(NullValue.NULL_VALUE).build());
		}

		@Test
		void mapsWithVariousKeyAndValueTypes() throws Exception {
			assertMatchesReference(TestAllTypesProto3.newBuilder().putMapInt32Int32(1, 100).putMapInt64Int64(2L, 200L)
					.putMapBoolBool(true, false).putMapStringString("k", "v")
					.putMapStringNestedMessage("n", TestAllTypesProto3.NestedMessage.newBuilder().setA(9).build())
					.putMapStringNestedEnum("e", TestAllTypesProto3.NestedEnum.BAR).build());
		}

		@Test
		void wrapperTypes() throws Exception {
			assertMatchesReference(TestAllTypesProto3.newBuilder().setOptionalInt32Wrapper(Int32Value.of(0))
					.setOptionalStringWrapper(StringValue.of("wrapped")).setOptionalBoolWrapper(BoolValue.of(true))
					.build());
		}

		@Test
		void wellKnownTypeFields() throws Exception {
			assertMatchesReference(TestAllTypesProto3.newBuilder()
					.setOptionalTimestamp(Timestamp.newBuilder().setSeconds(1136214245).setNanos(500000000))
					.setOptionalDuration(Duration.newBuilder().setSeconds(3).setNanos(250000000))
					.setOptionalFieldMask(FieldMask.newBuilder().addPaths("foo_bar").addPaths("baz"))
					.setOptionalStruct(
							Struct.newBuilder().putFields("s", Value.newBuilder().setStringValue("v").build()))
					.setOptionalValue(Value.newBuilder().setNumberValue(1.5).build()).build());
		}

		@Test
		void nestedAndRecursive() throws Exception {
			assertMatchesReference(TestAllTypesProto3.newBuilder()
					.setOptionalNestedMessage(TestAllTypesProto3.NestedMessage.newBuilder().setA(1)
							.setCorecursive(TestAllTypesProto3.newBuilder().setOptionalInt32(2)))
					.setRecursiveMessage(TestAllTypesProto3.newBuilder().setOptionalInt32(3)
							.setRecursiveMessage(TestAllTypesProto3.newBuilder().setOptionalInt32(4)))
					.build());
		}

		private TestAllTypesProto3 richSample() {
			return TestAllTypesProto3.newBuilder().setOptionalInt32(-42).setOptionalInt64(-123456789012345L)
					.setOptionalUint32(-1).setOptionalUint64(-1L).setOptionalSint32(-7).setOptionalSint64(-8L)
					.setOptionalFixed32(9).setOptionalFixed64(10L).setOptionalSfixed32(-11).setOptionalSfixed64(-12L)
					.setOptionalFloat(3.5f).setOptionalDouble(2.5).setOptionalBool(true)
					.setOptionalString("héllo \"world\"\n").setOptionalBytes(ByteString.copyFromUtf8("bytes"))
					.setOptionalNestedMessage(TestAllTypesProto3.NestedMessage.newBuilder().setA(17)
							.setCorecursive(TestAllTypesProto3.newBuilder().setOptionalInt32(99)))
					.setOptionalNestedEnum(TestAllTypesProto3.NestedEnum.NEG)
					.setOptionalForeignEnum(ForeignEnum.FOREIGN_BAR)
					.setOptionalAliasedEnum(TestAllTypesProto3.AliasedEnum.MOO).setFieldname1(1).setFieldName2(2)
					.setFieldName3(3).setField0Name5(5).setFieldName7(7).setFIELDNAME11(11).addRepeatedInt32(1)
					.addRepeatedInt32(2).addRepeatedString("a").addRepeatedString("b")
					.addRepeatedNestedEnum(TestAllTypesProto3.NestedEnum.FOO)
					.addRepeatedNestedEnum(TestAllTypesProto3.NestedEnum.NEG).putMapInt32Int32(1, 100)
					.putMapStringString("k", "v")
					.putMapStringNestedMessage("n", TestAllTypesProto3.NestedMessage.newBuilder().setA(3).build())
					.putMapBoolBool(true, false).setOptionalInt32Wrapper(Int32Value.of(0))
					.setOptionalStringWrapper(StringValue.of("ws")).setOptionalBoolWrapper(BoolValue.of(true))
					.setOptionalTimestamp(Timestamp.newBuilder().setSeconds(1136214245))
					.setOptionalDuration(Duration.newBuilder().setSeconds(3).setNanos(500000000))
					.setOptionalFieldMask(FieldMask.newBuilder().addPaths("foo_bar").addPaths("baz"))
					.setOptionalStruct(
							Struct.newBuilder().putFields("s", Value.newBuilder().setStringValue("v").build()))
					.setOptionalValue(Value.newBuilder().setNumberValue(1.5).build()).setOneofString("oneof").build();
		}
	}
}
