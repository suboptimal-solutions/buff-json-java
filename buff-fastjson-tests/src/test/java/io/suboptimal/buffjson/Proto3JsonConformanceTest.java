package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.*;
import com.google.protobuf.util.JsonFormat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.proto.*;

class Proto3JsonConformanceTest {

	private static final JsonFormat.Printer REFERENCE = JsonFormat.printer().omittingInsignificantWhitespace();

	private void assertMatchesReference(Message message) throws Exception {
		String expected = REFERENCE.print(message);
		String actual = BuffJSON.encode(message);
		assertEquals(expected, actual, "Mismatch for " + message.getDescriptorForType().getFullName());
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

		private static final Encoder ENCODER = BuffJSON.encoder().withTypeRegistry(TYPE_REGISTRY);

		private void assertAnyMatchesReference(Message message) throws Exception {
			String expected = ANY_REFERENCE.print(message);
			String actual = ENCODER.encode(message);
			assertEquals(expected, actual, "Mismatch for " + message.getDescriptorForType().getFullName());
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
}
