package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson2.JSONException;
import com.google.protobuf.*;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf_test_messages.proto3.TestMessagesProto3.ForeignEnum;
import com.google.protobuf_test_messages.proto3.TestMessagesProto3.TestAllTypesProto3;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.proto.*;

class BuffJsonProto3DecodeConformanceTest {

	private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();
	private static final BuffJsonDecoder CODEGEN_DECODER = BuffJson.decoder().setGeneratedDecoders(true);
	private static final BuffJsonDecoder RUNTIME_DECODER = BuffJson.decoder().setGeneratedDecoders(false);

	/**
	 * Round-trip test: serialize with JsonFormat.printer(), then deserialize with
	 * BuffJson.decode() and compare against the original message.
	 */
	private <T extends Message> void assertDecodeMatchesOriginal(T original) throws Exception {
		String json = PRINTER.print(original);
		@SuppressWarnings("unchecked")
		Class<T> clazz = (Class<T>) original.getClass();
		String typeName = original.getDescriptorForType().getFullName();

		// Test codegen path
		T codegen = CODEGEN_DECODER.decode(json, clazz);
		assertEquals(original, codegen, "Codegen mismatch for " + typeName + " json=" + json);

		// Test runtime path
		T runtime = RUNTIME_DECODER.decode(json, clazz);
		assertEquals(original, runtime, "Runtime mismatch for " + typeName + " json=" + json);
	}

	/**
	 * Asserts that malformed JSON is rejected with {@link JSONException} on both
	 * decode paths (codegen + runtime). Used for proto3 strictness edge cases where
	 * lenient acceptance would silently corrupt data.
	 */
	private void assertBothPathsReject(String json, Class<? extends Message> clazz) {
		assertThrows(JSONException.class, () -> CODEGEN_DECODER.decode(json, clazz), "codegen should reject: " + json);
		assertThrows(JSONException.class, () -> RUNTIME_DECODER.decode(json, clazz), "runtime should reject: " + json);
	}

	// =========================================================================
	// Scalar types
	// =========================================================================
	@Nested
	class ScalarTypes {

		@Test
		void allScalarTypes() throws Exception {
			assertDecodeMatchesOriginal(TestAllScalars.newBuilder().setOptionalInt32(42)
					.setOptionalInt64(123456789012345L).setOptionalUint32(100).setOptionalUint64(999999999999L)
					.setOptionalSint32(-42).setOptionalSint64(-123456789012345L).setOptionalFixed32(100)
					.setOptionalFixed64(999999999999L).setOptionalSfixed32(-100).setOptionalSfixed64(-999999999999L)
					.setOptionalFloat(3.14f).setOptionalDouble(2.718281828).setOptionalBool(true)
					.setOptionalString("hello world").setOptionalBytes(ByteString.copyFromUtf8("binary data")).build());
		}

		@Test
		void defaultValuesOmitted() throws Exception {
			assertDecodeMatchesOriginal(TestAllScalars.getDefaultInstance());
		}

		@Test
		void integerBoundaries() throws Exception {
			assertDecodeMatchesOriginal(TestAllScalars.newBuilder().setOptionalInt32(Integer.MAX_VALUE)
					.setOptionalInt64(Long.MAX_VALUE).setOptionalUint32(-1) // unsigned max = 4294967295
					.setOptionalUint64(-1L) // unsigned max
					.setOptionalSint32(Integer.MIN_VALUE).setOptionalSint64(Long.MIN_VALUE).build());
		}

		@Test
		void negativeNumbers() throws Exception {
			assertDecodeMatchesOriginal(TestAllScalars.newBuilder().setOptionalInt32(-1).setOptionalInt64(-1L)
					.setOptionalSint32(-1).setOptionalSint64(-1L).setOptionalSfixed32(-1).setOptionalSfixed64(-1L)
					.setOptionalFloat(-3.14f).setOptionalDouble(-2.718).build());
		}

		@Test
		void specialFloatValues() throws Exception {
			assertDecodeMatchesOriginal(
					TestAllScalars.newBuilder().setOptionalFloat(Float.NaN).setOptionalDouble(Double.NaN).build());
		}

		@Test
		void infinityValues() throws Exception {
			assertDecodeMatchesOriginal(TestAllScalars.newBuilder().setOptionalFloat(Float.POSITIVE_INFINITY)
					.setOptionalDouble(Double.NEGATIVE_INFINITY).build());
		}

		@Test
		void unicodeStrings() throws Exception {
			assertDecodeMatchesOriginal(TestAllScalars.newBuilder().setOptionalString("Hello \u4e16\u754c").build());
		}

		@Test
		void stringWithEscapeChars() throws Exception {
			assertDecodeMatchesOriginal(
					TestAllScalars.newBuilder().setOptionalString("line1\nline2\ttab\\backslash\"quote").build());
		}

		@Test
		void bytesWithPaddingEdgeCases() throws Exception {
			assertDecodeMatchesOriginal(
					TestAllScalars.newBuilder().setOptionalBytes(ByteString.copyFrom(new byte[]{0x01})).build());
			assertDecodeMatchesOriginal(
					TestAllScalars.newBuilder().setOptionalBytes(ByteString.copyFrom(new byte[]{0x01, 0x02})).build());
			assertDecodeMatchesOriginal(TestAllScalars.newBuilder()
					.setOptionalBytes(ByteString.copyFrom(new byte[]{0x01, 0x02, 0x03})).build());
		}

		// Edge case: uint64 may be an unquoted JSON number, not just a quoted string.
		// The max
		// value 2^64-1 overflows a signed long and is read via BigInteger; its bit
		// pattern is
		// -1L. (Conformance: Required.Proto3.JsonInput.Uint64FieldMaxValueNotQuoted.)
		// JsonFormat
		// always emits the quoted form, so these hand-write the unquoted JSON.

		@Test
		void unquotedMaxUint64() throws Exception {
			String json = "{\"optionalUint64\":18446744073709551615}";
			assertEquals(-1L, CODEGEN_DECODER.decode(json, TestAllScalars.class).getOptionalUint64(), "codegen");
			assertEquals(-1L, RUNTIME_DECODER.decode(json, TestAllScalars.class).getOptionalUint64(), "runtime");
		}

		@Test
		void quotedMaxUint64() throws Exception {
			String json = "{\"optionalUint64\":\"18446744073709551615\"}";
			assertEquals(-1L, CODEGEN_DECODER.decode(json, TestAllScalars.class).getOptionalUint64(), "codegen");
			assertEquals(-1L, RUNTIME_DECODER.decode(json, TestAllScalars.class).getOptionalUint64(), "runtime");
		}

		@Test
		void smallUnquotedUint64() throws Exception {
			String json = "{\"optionalUint64\":42}";
			assertEquals(42L, CODEGEN_DECODER.decode(json, TestAllScalars.class).getOptionalUint64(), "codegen");
			assertEquals(42L, RUNTIME_DECODER.decode(json, TestAllScalars.class).getOptionalUint64(), "runtime");
		}

		// Edge cases: strict proto3 scalar parsing — lenient acceptance here would
		// silently
		// corrupt data (truncate, overflow, or coerce a wrong type to a plausible
		// value), so
		// both decode paths must reject. (Conformance:
		// Int32/Uint32Field{NotInteger,NotNumber,
		// EmptyString,TooLarge}, StringFieldNotAString.)

		@Test
		void int32RejectsNonInteger() {
			assertBothPathsReject("{\"optionalInt32\":1.5}", TestAllScalars.class);
		}

		@Test
		void int32RejectsEmptyString() {
			assertBothPathsReject("{\"optionalInt32\":\"\"}", TestAllScalars.class);
		}

		@Test
		void int32RejectsWrongType() {
			assertBothPathsReject("{\"optionalInt32\":true}", TestAllScalars.class);
			assertBothPathsReject("{\"optionalInt32\":\"abc\"}", TestAllScalars.class);
		}

		@Test
		void int32RejectsOutOfRange() {
			assertBothPathsReject("{\"optionalInt32\":2147483648}", TestAllScalars.class); // max + 1
		}

		@Test
		void uint32RejectsTooLarge() {
			assertBothPathsReject("{\"optionalUint32\":4294967296}", TestAllScalars.class); // 2^32
		}

		@Test
		void uint32RejectsNegative() {
			assertBothPathsReject("{\"optionalUint32\":-1}", TestAllScalars.class);
		}

		@Test
		void stringRejectsNonString() {
			assertBothPathsReject("{\"optionalString\":123}", TestAllScalars.class);
			assertBothPathsReject("{\"optionalString\":true}", TestAllScalars.class);
		}

		// Don't over-reject: the strict reader must still accept the valid forms proto3
		// allows.

		@Test
		void int32AcceptsIntegralFloatAndQuotedString() throws Exception {
			// 2.0 (integral float) and "42" (quoted integer string) are both valid for
			// int32.
			assertEquals(2, CODEGEN_DECODER.decode("{\"optionalInt32\":2.0}", TestAllScalars.class).getOptionalInt32());
			assertEquals(2, RUNTIME_DECODER.decode("{\"optionalInt32\":2.0}", TestAllScalars.class).getOptionalInt32());
			assertEquals(42,
					CODEGEN_DECODER.decode("{\"optionalInt32\":\"42\"}", TestAllScalars.class).getOptionalInt32());
			assertEquals(42,
					RUNTIME_DECODER.decode("{\"optionalInt32\":\"42\"}", TestAllScalars.class).getOptionalInt32());
		}

		@Test
		void uint32AcceptsMax() throws Exception {
			// 4294967295 = 2^32-1 is the valid max; its signed-int bit pattern is -1.
			assertEquals(-1, CODEGEN_DECODER.decode("{\"optionalUint32\":4294967295}", TestAllScalars.class)
					.getOptionalUint32());
			assertEquals(-1, RUNTIME_DECODER.decode("{\"optionalUint32\":4294967295}", TestAllScalars.class)
					.getOptionalUint32());
		}
	}

	// =========================================================================
	// Repeated fields
	// =========================================================================
	@Nested
	class RepeatedFields {

		@Test
		void repeatedScalars() throws Exception {
			assertDecodeMatchesOriginal(TestRepeatedScalars.newBuilder().addRepeatedInt32(1).addRepeatedInt32(2)
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
			assertDecodeMatchesOriginal(TestRepeatedScalars.getDefaultInstance());
		}

		@Test
		void singleElementRepeated() throws Exception {
			assertDecodeMatchesOriginal(TestRepeatedScalars.newBuilder().addRepeatedInt32(42).build());
		}

		// Edge cases: a repeated element of the wrong JSON type must be rejected, not
		// coerced
		// (the per-element read goes through the same strict scalar readers).
		// (Conformance:
		// RepeatedFieldWrongElementTypeExpecting{Integers,Strings}Got{Bool,Int,Message}.)

		@Test
		void repeatedIntRejectsWrongElementType() {
			assertBothPathsReject("{\"repeatedInt32\":[true]}", TestRepeatedScalars.class);
			assertBothPathsReject("{\"repeatedInt32\":[{}]}", TestRepeatedScalars.class);
			assertBothPathsReject("{\"repeatedInt32\":[1.5]}", TestRepeatedScalars.class);
		}

		@Test
		void repeatedStringRejectsWrongElementType() {
			assertBothPathsReject("{\"repeatedString\":[123]}", TestRepeatedScalars.class);
			assertBothPathsReject("{\"repeatedString\":[true]}", TestRepeatedScalars.class);
			assertBothPathsReject("{\"repeatedString\":[{}]}", TestRepeatedScalars.class);
		}
	}

	// =========================================================================
	// Enums
	// =========================================================================
	@Nested
	class Enums {

		@Test
		void enumValues() throws Exception {
			assertDecodeMatchesOriginal(TestNesting.newBuilder().setEnumValue(TestEnum.TEST_ENUM_FOO).build());
		}

		@Test
		void defaultEnumOmitted() throws Exception {
			assertDecodeMatchesOriginal(TestNesting.newBuilder().setEnumValue(TestEnum.TEST_ENUM_UNSPECIFIED).build());
		}

		@Test
		void repeatedEnums() throws Exception {
			assertDecodeMatchesOriginal(TestNesting.newBuilder().addRepeatedEnum(TestEnum.TEST_ENUM_FOO)
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
			assertDecodeMatchesOriginal(TestNesting.newBuilder()
					.setNested(NestedMessage.newBuilder().setValue(42).setName("test")).build());
		}

		@Test
		void repeatedNestedMessages() throws Exception {
			assertDecodeMatchesOriginal(
					TestNesting.newBuilder().addRepeatedNested(NestedMessage.newBuilder().setValue(1).setName("a"))
							.addRepeatedNested(NestedMessage.newBuilder().setValue(2).setName("b")).build());
		}

		@Test
		void emptyNestedMessage() throws Exception {
			assertDecodeMatchesOriginal(TestNesting.newBuilder().setNested(NestedMessage.getDefaultInstance()).build());
		}

		@Test
		void recursiveMessage() throws Exception {
			assertDecodeMatchesOriginal(TestRecursive.newBuilder().setValue(1)
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
			assertDecodeMatchesOriginal(TestOneof.newBuilder().setName("test").setIntValue(42).build());
		}

		@Test
		void oneofString() throws Exception {
			assertDecodeMatchesOriginal(TestOneof.newBuilder().setStringValue("hello").build());
		}

		@Test
		void oneofBool() throws Exception {
			assertDecodeMatchesOriginal(TestOneof.newBuilder().setBoolValue(true).build());
		}

		@Test
		void oneofMessage() throws Exception {
			assertDecodeMatchesOriginal(
					TestOneof.newBuilder().setMessageValue(NestedMessage.newBuilder().setValue(42)).build());
		}

		@Test
		void oneofEnum() throws Exception {
			assertDecodeMatchesOriginal(TestOneof.newBuilder().setEnumValue(TestEnum.TEST_ENUM_BAR).build());
		}

		@Test
		void oneofNotSet() throws Exception {
			assertDecodeMatchesOriginal(TestOneof.newBuilder().setName("no oneof").build());
		}

		@Test
		void oneofWithDefaultValue() throws Exception {
			assertDecodeMatchesOriginal(TestOneof.newBuilder().setIntValue(0).build());
		}
	}

	// =========================================================================
	// Map fields
	// =========================================================================
	@Nested
	class MapFields {

		@Test
		void stringKeyMaps() throws Exception {
			assertDecodeMatchesOriginal(TestMaps.newBuilder().putStringToString("key1", "value1")
					.putStringToString("key2", "value2").putStringToInt32("count", 42)
					.putStringToInt64("big", 999999999999L).putStringToFloat("pi", 3.14f).putStringToDouble("e", 2.718)
					.putStringToBool("flag", true).putStringToBytes("data", ByteString.copyFromUtf8("binary"))
					.putStringToMessage("msg", NestedMessage.newBuilder().setValue(1).build())
					.putStringToEnum("status", TestEnum.TEST_ENUM_FOO).build());
		}

		@Test
		void intKeyMaps() throws Exception {
			assertDecodeMatchesOriginal(TestMaps.newBuilder().putInt32ToString(1, "one").putInt32ToString(2, "two")
					.putInt64ToString(100L, "hundred").putUint32ToString(42, "answer").putUint64ToString(999L, "big")
					.putSint32ToString(-1, "negative").putSint64ToString(-100L, "neg hundred")
					.putFixed32ToString(10, "fixed").putFixed64ToString(20L, "fixed64")
					.putSfixed32ToString(-10, "sfixed").putSfixed64ToString(-20L, "sfixed64").build());
		}

		@Test
		void boolKeyMap() throws Exception {
			assertDecodeMatchesOriginal(
					TestMaps.newBuilder().putBoolToString(true, "yes").putBoolToString(false, "no").build());
		}

		@Test
		void emptyMapsOmitted() throws Exception {
			assertDecodeMatchesOriginal(TestMaps.getDefaultInstance());
		}

		@Test
		void singleEntryMap() throws Exception {
			assertDecodeMatchesOriginal(TestMaps.newBuilder().putStringToString("only", "one").build());
		}
	}

	// =========================================================================
	// Well-known types: Timestamp
	// =========================================================================
	@Nested
	class TimestampTests {

		@Test
		void timestamp() throws Exception {
			assertDecodeMatchesOriginal(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1711627200).setNanos(0)).build());
		}

		@Test
		void timestampWithNanos() throws Exception {
			assertDecodeMatchesOriginal(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1711627200).setNanos(123456000)).build());
		}

		@Test
		void timestampWithFullNanos() throws Exception {
			assertDecodeMatchesOriginal(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1711627200).setNanos(123456789)).build());
		}

		@Test
		void timestampEpoch() throws Exception {
			assertDecodeMatchesOriginal(
					TestTimestamp.newBuilder().setValue(Timestamp.newBuilder().setSeconds(0).setNanos(0)).build());
		}

		@Test
		void timestampMinBoundaryAccepted() throws Exception {
			// 0001-01-01T00:00:00Z is the proto3 minimum — must still round-trip (no
			// over-rejection from the new parse-time range guard).
			assertDecodeMatchesOriginal(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(-62135596800L).setNanos(0)).build());
		}

		@Test
		void timestampBelowMinRejected() {
			// 0000-01-01T00:00:00Z parses as a valid RFC 3339 instant but is below the
			// proto3 Timestamp minimum (0001-01-01). Rejected at parse time on both
			// paths. Conformance: Required.Proto3.JsonInput.TimestampJsonInputTooSmall.
			assertBothPathsReject("{\"value\":\"0000-01-01T00:00:00Z\"}", TestTimestamp.class);
		}
	}

	// =========================================================================
	// Well-known types: Duration
	// =========================================================================
	@Nested
	class DurationTests {

		@Test
		void duration() throws Exception {
			assertDecodeMatchesOriginal(
					TestDuration.newBuilder().setValue(Duration.newBuilder().setSeconds(3600).setNanos(0)).build());
		}

		@Test
		void durationWithNanos() throws Exception {
			assertDecodeMatchesOriginal(TestDuration.newBuilder()
					.setValue(Duration.newBuilder().setSeconds(1).setNanos(500000000)).build());
		}

		@Test
		void durationNegative() throws Exception {
			assertDecodeMatchesOriginal(TestDuration.newBuilder()
					.setValue(Duration.newBuilder().setSeconds(-1).setNanos(-500000000)).build());
		}

		@Test
		void durationZero() throws Exception {
			assertDecodeMatchesOriginal(
					TestDuration.newBuilder().setValue(Duration.newBuilder().setSeconds(0).setNanos(0)).build());
		}

		@Test
		void durationMaxBoundaryAccepted() throws Exception {
			// The exact proto3 maximum (±315576000000s) must still round-trip (no
			// over-rejection from the new parse-time range guard).
			assertDecodeMatchesOriginal(TestDuration.newBuilder()
					.setValue(Duration.newBuilder().setSeconds(315576000000L).setNanos(0)).build());
		}

		@Test
		void durationBelowMinRejected() {
			// One second below the proto3 Duration minimum (-315576000000s). Rejected at
			// parse time. Conformance: Required.Proto3.JsonInput.DurationJsonInputTooSmall.
			assertBothPathsReject("{\"value\":\"-315576000001.000000000s\"}", TestDuration.class);
		}

		@Test
		void durationAboveMaxRejected() {
			// One second above the proto3 Duration maximum (315576000000s). Rejected at
			// parse time. Conformance: Required.Proto3.JsonInput.DurationJsonInputTooLarge.
			assertBothPathsReject("{\"value\":\"315576000001.000000000s\"}", TestDuration.class);
		}
	}

	// =========================================================================
	// Well-known types: FieldMask
	// =========================================================================
	@Nested
	class FieldMaskTests {

		@Test
		void fieldMask() throws Exception {
			assertDecodeMatchesOriginal(TestFieldMask.newBuilder()
					.setValue(FieldMask.newBuilder().addPaths("foo").addPaths("bar_baz").addPaths("qux.quux")).build());
		}

		@Test
		void emptyFieldMask() throws Exception {
			assertDecodeMatchesOriginal(TestFieldMask.newBuilder().setValue(FieldMask.getDefaultInstance()).build());
		}
	}

	// =========================================================================
	// Well-known types: Struct, Value, ListValue
	// =========================================================================
	@Nested
	class StructTests {

		@Test
		void struct() throws Exception {
			assertDecodeMatchesOriginal(
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
			assertDecodeMatchesOriginal(
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
			assertDecodeMatchesOriginal(
					TestStruct.newBuilder().setValue(Value.newBuilder().setStringValue("test")).build());
		}

		@Test
		void valueNumber() throws Exception {
			assertDecodeMatchesOriginal(
					TestStruct.newBuilder().setValue(Value.newBuilder().setNumberValue(3.14)).build());
		}

		@Test
		void valueBool() throws Exception {
			assertDecodeMatchesOriginal(
					TestStruct.newBuilder().setValue(Value.newBuilder().setBoolValue(false)).build());
		}

		@Test
		void valueNull() throws Exception {
			assertDecodeMatchesOriginal(
					TestStruct.newBuilder().setValue(Value.newBuilder().setNullValue(NullValue.NULL_VALUE)).build());
		}

		@Test
		void listValue() throws Exception {
			assertDecodeMatchesOriginal(TestStruct.newBuilder()
					.setListValue(ListValue.newBuilder().addValues(Value.newBuilder().setStringValue("a"))
							.addValues(Value.newBuilder().setNumberValue(1.0))
							.addValues(Value.newBuilder().setBoolValue(true))
							.addValues(Value.newBuilder().setNullValue(NullValue.NULL_VALUE)))
					.build());
		}

		@Test
		void emptyStruct() throws Exception {
			assertDecodeMatchesOriginal(TestStruct.newBuilder().setStructValue(Struct.getDefaultInstance()).build());
		}
	}

	// =========================================================================
	// Well-known types: Wrappers
	// =========================================================================
	@Nested
	class WrapperTests {

		@Test
		void int32Wrapper() throws Exception {
			assertDecodeMatchesOriginal(TestWrappers.newBuilder().setInt32Value(Int32Value.of(42)).build());
		}

		@Test
		void uint32Wrapper() throws Exception {
			assertDecodeMatchesOriginal(TestWrappers.newBuilder().setUint32Value(UInt32Value.of(100)).build());
		}

		@Test
		void int64Wrapper() throws Exception {
			assertDecodeMatchesOriginal(
					TestWrappers.newBuilder().setInt64Value(Int64Value.of(123456789012345L)).build());
		}

		@Test
		void uint64Wrapper() throws Exception {
			assertDecodeMatchesOriginal(
					TestWrappers.newBuilder().setUint64Value(UInt64Value.of(999999999999L)).build());
		}

		@Test
		void floatWrapper() throws Exception {
			assertDecodeMatchesOriginal(TestWrappers.newBuilder().setFloatValue(FloatValue.of(3.14f)).build());
		}

		@Test
		void doubleWrapper() throws Exception {
			assertDecodeMatchesOriginal(TestWrappers.newBuilder().setDoubleValue(DoubleValue.of(2.718)).build());
		}

		@Test
		void boolWrapper() throws Exception {
			assertDecodeMatchesOriginal(TestWrappers.newBuilder().setBoolValue(BoolValue.of(true)).build());
		}

		@Test
		void stringWrapper() throws Exception {
			assertDecodeMatchesOriginal(TestWrappers.newBuilder().setStringValue(StringValue.of("hello")).build());
		}

		@Test
		void bytesWrapper() throws Exception {
			assertDecodeMatchesOriginal(
					TestWrappers.newBuilder().setBytesValue(BytesValue.of(ByteString.copyFromUtf8("binary"))).build());
		}

		@Test
		void wrapperWithZeroValue() throws Exception {
			assertDecodeMatchesOriginal(TestWrappers.newBuilder().setInt32Value(Int32Value.of(0))
					.setStringValue(StringValue.of("")).setBoolValue(BoolValue.of(false)).build());
		}

		@Test
		void allWrappers() throws Exception {
			assertDecodeMatchesOriginal(
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
			assertDecodeMatchesOriginal(
					TestOptionalFields.newBuilder().setOptionalInt32(42).setOptionalString("present")
							.setOptionalBool(true).setOptionalEnum(TestEnum.TEST_ENUM_FOO).build());
		}

		@Test
		void optionalFieldSetToDefault() throws Exception {
			assertDecodeMatchesOriginal(TestOptionalFields.newBuilder().setOptionalInt32(0).setOptionalString("")
					.setOptionalBool(false).setOptionalEnum(TestEnum.TEST_ENUM_UNSPECIFIED).build());
		}

		@Test
		void optionalFieldNotSet() throws Exception {
			assertDecodeMatchesOriginal(TestOptionalFields.getDefaultInstance());
		}
	}

	// =========================================================================
	// Custom JSON name
	// =========================================================================
	@Nested
	class CustomJsonName {

		@Test
		void customJsonName() throws Exception {
			assertDecodeMatchesOriginal(TestCustomJsonName.newBuilder().setValue(42).setName("test").build());
		}
	}

	// =========================================================================
	// Well-known types: Any
	// =========================================================================
	@Nested
	class AnyTests {

		private static final TypeRegistry TYPE_REGISTRY = TypeRegistry.newBuilder().add(TestAllScalars.getDescriptor())
				.add(Duration.getDescriptor()).add(Timestamp.getDescriptor()).add(TestAny.getDescriptor()).build();

		private static final JsonFormat.Printer ANY_PRINTER = JsonFormat.printer().usingTypeRegistry(TYPE_REGISTRY)
				.omittingInsignificantWhitespace();

		private static final BuffJsonDecoder DECODER = BuffJson.decoder().setTypeRegistry(TYPE_REGISTRY);
		private static final BuffJsonDecoder RUNTIME_ANY_DECODER = DECODER.setGeneratedDecoders(false);

		private <T extends Message> void assertAnyDecodeMatchesOriginal(T original) throws Exception {
			String json = ANY_PRINTER.print(original);
			@SuppressWarnings("unchecked")
			Class<T> clazz = (Class<T>) original.getClass();
			String typeName = original.getDescriptorForType().getFullName();

			T codegen = DECODER.decode(json, clazz);
			assertEquals(original, codegen, "Codegen mismatch for " + typeName + " json=" + json);

			T runtime = RUNTIME_ANY_DECODER.decode(json, clazz);
			assertEquals(original, runtime, "Runtime mismatch for " + typeName + " json=" + json);
		}

		@Test
		void anyContainingRegularMessage() throws Exception {
			TestAllScalars inner = TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalString("hello")
					.setOptionalBool(true).build();
			assertAnyDecodeMatchesOriginal(TestAny.newBuilder().setValue(Any.pack(inner)).build());
		}

		@Test
		void anyContainingDuration() throws Exception {
			Duration inner = Duration.newBuilder().setSeconds(3600).setNanos(500000000).build();
			assertAnyDecodeMatchesOriginal(TestAny.newBuilder().setValue(Any.pack(inner)).build());
		}

		@Test
		void anyContainingTimestamp() throws Exception {
			Timestamp inner = Timestamp.newBuilder().setSeconds(1711627200).setNanos(123000000).build();
			assertAnyDecodeMatchesOriginal(TestAny.newBuilder().setValue(Any.pack(inner)).build());
		}

		@Test
		void emptyAny() throws Exception {
			assertAnyDecodeMatchesOriginal(TestAny.getDefaultInstance());
		}

		@Test
		void anyWithEmptyTypeAndValueRejected() {
			// An empty "@type" is unresolvable; only a bare {} is a valid typeless Any, so
			// a non-empty Any object with empty @type is rejected on both decode paths.
			// Conformance:
			// Required.Proto3.JsonInput.AnyWktRepresentationWithEmptyTypeAndValue.
			String json = "{\"value\": {\"@type\": \"\", \"value\": \"\"}}";
			assertThrows(JSONException.class, () -> DECODER.decode(json, TestAny.class),
					"codegen should reject empty @type");
			assertThrows(JSONException.class, () -> RUNTIME_ANY_DECODER.decode(json, TestAny.class),
					"runtime should reject empty @type");
		}

		@Test
		void emptyAnyObjectAccepted() throws Exception {
			// A bare {} for the Any field is the valid typeless empty Any — must NOT be
			// rejected by the empty-@type guard (round-trips to a default Any).
			assertAnyDecodeMatchesOriginal(TestAny.newBuilder().setValue(Any.getDefaultInstance()).build());
		}

		@Test
		void anyWithDefaultInnerMessage() throws Exception {
			assertAnyDecodeMatchesOriginal(
					TestAny.newBuilder().setValue(Any.pack(TestAllScalars.getDefaultInstance())).build());
		}

		@Test
		void anyContainingAny() throws Exception {
			TestAllScalars inner = TestAllScalars.newBuilder().setOptionalInt32(1).build();
			Any wrappedInner = Any.pack(inner);
			assertAnyDecodeMatchesOriginal(TestAny.newBuilder().setValue(Any.pack(wrappedInner)).build());
		}

		/** Encode with BuffJson, decode with BuffJson — true round-trip. */
		@Test
		void roundTripAnyContainingRegularMessage() {
			TestAllScalars inner = TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalString("hello")
					.setOptionalBool(true).build();
			TestAny original = TestAny.newBuilder().setValue(Any.pack(inner)).build();

			BuffJsonEncoder encoder = BuffJson.encoder().setTypeRegistry(TYPE_REGISTRY);
			String json = encoder.encode(original);

			TestAny codegen = DECODER.decode(json, TestAny.class);
			assertEquals(original, codegen, "Round-trip codegen mismatch, json=" + json);

			TestAny runtime = RUNTIME_ANY_DECODER.decode(json, TestAny.class);
			assertEquals(original, runtime, "Round-trip runtime mismatch, json=" + json);
		}

		/** Encode with BuffJson, decode with BuffJson — Any containing WKT. */
		@Test
		void roundTripAnyContainingTimestamp() {
			Timestamp inner = Timestamp.newBuilder().setSeconds(1711627200).setNanos(123000000).build();
			TestAny original = TestAny.newBuilder().setValue(Any.pack(inner)).build();

			BuffJsonEncoder encoder = BuffJson.encoder().setTypeRegistry(TYPE_REGISTRY);
			String json = encoder.encode(original);

			TestAny codegen = DECODER.decode(json, TestAny.class);
			assertEquals(original, codegen, "Round-trip codegen mismatch, json=" + json);

			TestAny runtime = RUNTIME_ANY_DECODER.decode(json, TestAny.class);
			assertEquals(original, runtime, "Round-trip runtime mismatch, json=" + json);
		}

		/** Encode with BuffJson, decode with BuffJson — nested Any. */
		@Test
		void roundTripAnyContainingAny() {
			TestAllScalars inner = TestAllScalars.newBuilder().setOptionalInt32(1).build();
			Any wrappedInner = Any.pack(inner);
			TestAny original = TestAny.newBuilder().setValue(Any.pack(wrappedInner)).build();

			BuffJsonEncoder encoder = BuffJson.encoder().setTypeRegistry(TYPE_REGISTRY);
			String json = encoder.encode(original);

			TestAny codegen = DECODER.decode(json, TestAny.class);
			assertEquals(original, codegen, "Round-trip codegen mismatch, json=" + json);

			TestAny runtime = RUNTIME_ANY_DECODER.decode(json, TestAny.class);
			assertEquals(original, runtime, "Round-trip runtime mismatch, json=" + json);
		}
	}

	// =========================================================================
	// Well-known types: Empty
	// =========================================================================
	@Nested
	class EmptyTests {

		@Test
		void emptyMessage() throws Exception {
			assertDecodeMatchesOriginal(TestEmpty.newBuilder().setValue(Empty.getDefaultInstance()).build());
		}
	}

	// =========================================================================
	// Edge cases: empty messages
	// =========================================================================
	@Nested
	class EmptyMessages {

		@Test
		void emptyTestAllScalars() throws Exception {
			assertDecodeMatchesOriginal(TestAllScalars.getDefaultInstance());
		}

		@Test
		void emptyTestMaps() throws Exception {
			assertDecodeMatchesOriginal(TestMaps.getDefaultInstance());
		}

		@Test
		void emptyTestOneof() throws Exception {
			assertDecodeMatchesOriginal(TestOneof.getDefaultInstance());
		}

		@Test
		void emptyTestWrappers() throws Exception {
			assertDecodeMatchesOriginal(TestWrappers.getDefaultInstance());
		}

		@Test
		void emptyTestNesting() throws Exception {
			assertDecodeMatchesOriginal(TestNesting.getDefaultInstance());
		}
	}

	// =========================================================================
	// Round-trip: BuffJson.encode() → BuffJson.decode()
	// =========================================================================
	@Nested
	class RoundTrip {

		private static final BuffJsonEncoder CODEGEN_ENCODER = BuffJson.encoder().setGeneratedEncoders(true);
		private static final BuffJsonEncoder RUNTIME_ENCODER = BuffJson.encoder().setGeneratedEncoders(false);

		/** Encode with BuffJson, decode with BuffJson, assert equals original. */
		private <T extends Message> void assertRoundTrip(T original) {
			@SuppressWarnings("unchecked")
			Class<T> clazz = (Class<T>) original.getClass();

			// codegen encode → codegen decode
			String codegenJson = CODEGEN_ENCODER.encode(original);
			T codegenResult = CODEGEN_DECODER.decode(codegenJson, clazz);
			assertEquals(original, codegenResult,
					"Codegen round-trip for " + original.getDescriptorForType().getFullName());

			// runtime encode → runtime decode
			String runtimeJson = RUNTIME_ENCODER.encode(original);
			T runtimeResult = RUNTIME_DECODER.decode(runtimeJson, clazz);
			assertEquals(original, runtimeResult,
					"Runtime round-trip for " + original.getDescriptorForType().getFullName());

			// cross: codegen encode → runtime decode
			T crossResult = RUNTIME_DECODER.decode(codegenJson, clazz);
			assertEquals(original, crossResult,
					"Cross round-trip for " + original.getDescriptorForType().getFullName());
		}

		@Test
		void scalars() {
			assertRoundTrip(TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalInt64(123456789012345L)
					.setOptionalUint32(100).setOptionalUint64(999999999999L).setOptionalSint32(-42)
					.setOptionalSint64(-123456789012345L).setOptionalFixed32(100).setOptionalFixed64(999999999999L)
					.setOptionalSfixed32(-100).setOptionalSfixed64(-999999999999L).setOptionalFloat(3.14f)
					.setOptionalDouble(2.718281828).setOptionalBool(true).setOptionalString("hello world")
					.setOptionalBytes(ByteString.copyFromUtf8("binary data")).build());
		}

		@Test
		void nested() {
			assertRoundTrip(TestNesting.newBuilder().setNested(NestedMessage.newBuilder().setValue(42).setName("test"))
					.setEnumValue(TestEnum.TEST_ENUM_FOO)
					.addRepeatedNested(NestedMessage.newBuilder().setValue(1).setName("a"))
					.addRepeatedEnum(TestEnum.TEST_ENUM_BAR).build());
		}

		@Test
		void maps() {
			assertRoundTrip(TestMaps.newBuilder().putStringToString("k", "v").putInt32ToString(1, "one")
					.putBoolToString(true, "yes")
					.putStringToMessage("msg", NestedMessage.newBuilder().setValue(1).build())
					.putStringToEnum("e", TestEnum.TEST_ENUM_FOO).build());
		}

		@Test
		void oneof() {
			assertRoundTrip(TestOneof.newBuilder().setName("test").setIntValue(42).build());
			assertRoundTrip(TestOneof.newBuilder().setStringValue("hello").build());
			assertRoundTrip(TestOneof.newBuilder().setMessageValue(NestedMessage.newBuilder().setValue(1)).build());
		}

		@Test
		void wrappers() {
			assertRoundTrip(TestWrappers.newBuilder().setInt32Value(Int32Value.of(1)).setUint32Value(UInt32Value.of(2))
					.setInt64Value(Int64Value.of(3L)).setUint64Value(UInt64Value.of(4L))
					.setFloatValue(FloatValue.of(5.0f)).setDoubleValue(DoubleValue.of(6.0))
					.setBoolValue(BoolValue.of(true)).setStringValue(StringValue.of("eight"))
					.setBytesValue(BytesValue.of(ByteString.copyFromUtf8("nine"))).build());
		}

		@Test
		void timestamp() {
			assertRoundTrip(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1711627200).setNanos(123456789)).build());
		}

		@Test
		void duration() {
			assertRoundTrip(TestDuration.newBuilder()
					.setValue(Duration.newBuilder().setSeconds(-1).setNanos(-500000000)).build());
		}

		@Test
		void struct() {
			assertRoundTrip(
					TestStruct.newBuilder()
							.setStructValue(Struct.newBuilder()
									.putFields("string", Value.newBuilder().setStringValue("hello").build())
									.putFields("number", Value.newBuilder().setNumberValue(42.0).build())
									.putFields("null", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()))
							.build());
		}

		@Test
		void fieldMask() {
			assertRoundTrip(TestFieldMask.newBuilder()
					.setValue(FieldMask.newBuilder().addPaths("foo").addPaths("bar_baz")).build());
		}

		@Test
		void empty() {
			assertRoundTrip(TestEmpty.newBuilder().setValue(Empty.getDefaultInstance()).build());
		}

		@Test
		void optionalFields() {
			assertRoundTrip(TestOptionalFields.newBuilder().setOptionalInt32(0).setOptionalString("")
					.setOptionalBool(false).setOptionalEnum(TestEnum.TEST_ENUM_UNSPECIFIED).build());
		}

		@Test
		void customJsonName() {
			assertRoundTrip(TestCustomJsonName.newBuilder().setValue(42).setName("test").build());
		}
	}

	// =========================================================================
	// Official protobuf conformance message (protobuf_test_messages.proto3.
	// TestAllTypesProto3) — the same sample the conformance_test_runner drives.
	// Round-trip decode is order-agnostic (it parses JSON), so the full sample
	// including an interleaved oneof can be checked directly. A standalone
	// NullValue field is omitted (see the encode test note / failure_list.txt).
	// =========================================================================
	@Nested
	class OfficialProto3TestMessages {

		@Test
		void defaultInstance() throws Exception {
			assertDecodeMatchesOriginal(TestAllTypesProto3.getDefaultInstance());
		}

		@Test
		void fullyPopulated() throws Exception {
			assertDecodeMatchesOriginal(richSample());
		}

		@Test
		void negativeNestedEnum() throws Exception {
			assertDecodeMatchesOriginal(
					TestAllTypesProto3.newBuilder().setOptionalNestedEnum(TestAllTypesProto3.NestedEnum.NEG).build());
		}

		@Test
		void aliasedEnum() throws Exception {
			assertDecodeMatchesOriginal(
					TestAllTypesProto3.newBuilder().setOptionalAliasedEnum(TestAllTypesProto3.AliasedEnum.MOO).build());
		}

		@Test
		void multipleDistinctEnumTypes() throws Exception {
			assertDecodeMatchesOriginal(
					TestAllTypesProto3.newBuilder().setOptionalNestedEnum(TestAllTypesProto3.NestedEnum.BAR)
							.setOptionalForeignEnum(ForeignEnum.FOREIGN_BAZ)
							.setOptionalAliasedEnum(TestAllTypesProto3.AliasedEnum.ALIAS_BAR).build());
		}

		@Test
		void unknownNestedEnumNumberPreserved() throws Exception {
			// An unrecognized numeric enum value must round-trip (proto3 open enums
			// preserve it
			// rather than dropping to 0); JsonFormat prints it as a bare number.
			// (Conformance:
			// Required.Proto3.JsonInput.EnumFieldUnknownValue.)
			assertDecodeMatchesOriginal(TestAllTypesProto3.newBuilder().setOptionalNestedEnumValue(123).build());
		}

		@Test
		void unknownRepeatedNestedEnumNumberPreserved() throws Exception {
			assertDecodeMatchesOriginal(TestAllTypesProto3.newBuilder().addRepeatedNestedEnumValue(1)
					.addRepeatedNestedEnumValue(123).addRepeatedNestedEnumValue(2).build());
		}

		@Test
		void digitAndUnderscoreFieldNames() throws Exception {
			assertDecodeMatchesOriginal(
					TestAllTypesProto3.newBuilder().setFieldname1(1).setFieldName2(2).setFieldName3(3).setField0Name5(5)
							.setField0Name6(6).setFieldName7(7).setFIELDNAME11(11).setFIELDName12(12).build());
		}

		@Test
		void oneofVariants() throws Exception {
			assertDecodeMatchesOriginal(TestAllTypesProto3.newBuilder().setOneofUint32(7).build());
			assertDecodeMatchesOriginal(TestAllTypesProto3.newBuilder().setOneofString("picked").build());
			assertDecodeMatchesOriginal(
					TestAllTypesProto3.newBuilder().setOneofEnum(TestAllTypesProto3.NestedEnum.NEG).build());
			assertDecodeMatchesOriginal(TestAllTypesProto3.newBuilder()
					.setOneofNestedMessage(TestAllTypesProto3.NestedMessage.newBuilder().setA(5)).build());
			// JSON null round-trips back to a present google.protobuf.NullValue field.
			assertDecodeMatchesOriginal(
					TestAllTypesProto3.newBuilder().setOneofNullValue(NullValue.NULL_VALUE).build());
		}

		@Test
		void mapsWithVariousKeyAndValueTypes() throws Exception {
			assertDecodeMatchesOriginal(TestAllTypesProto3.newBuilder().putMapInt32Int32(1, 100)
					.putMapInt64Int64(2L, 200L).putMapBoolBool(true, false).putMapStringString("k", "v")
					.putMapStringNestedMessage("n", TestAllTypesProto3.NestedMessage.newBuilder().setA(9).build())
					.putMapStringNestedEnum("e", TestAllTypesProto3.NestedEnum.BAR).build());
		}

		@Test
		void wrapperTypes() throws Exception {
			assertDecodeMatchesOriginal(TestAllTypesProto3.newBuilder().setOptionalInt32Wrapper(Int32Value.of(0))
					.setOptionalStringWrapper(StringValue.of("wrapped")).setOptionalBoolWrapper(BoolValue.of(true))
					.build());
		}

		@Test
		void wellKnownTypeFields() throws Exception {
			assertDecodeMatchesOriginal(TestAllTypesProto3.newBuilder()
					.setOptionalTimestamp(Timestamp.newBuilder().setSeconds(1136214245).setNanos(500000000))
					.setOptionalDuration(Duration.newBuilder().setSeconds(3).setNanos(250000000))
					.setOptionalFieldMask(FieldMask.newBuilder().addPaths("foo_bar").addPaths("baz"))
					.setOptionalStruct(
							Struct.newBuilder().putFields("s", Value.newBuilder().setStringValue("v").build()))
					.setOptionalValue(Value.newBuilder().setNumberValue(1.5).build()).build());
		}

		@Test
		void nestedAndRecursive() throws Exception {
			assertDecodeMatchesOriginal(TestAllTypesProto3.newBuilder()
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
