package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.*;
import com.google.protobuf.util.JsonFormat;

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
}
