package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.*;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.proto.*;

/**
 * Error path tests for {@link BuffJson} encoder and decoder. Validates that
 * corrupted, incomplete, or type-mismatched inputs produce clear, actionable
 * error messages rather than silent corruption or cryptic stack traces.
 *
 * <p>
 * Tests run against both codegen and runtime decoder paths where applicable.
 */
class BuffJsonErrorTest {

	private static final BuffJsonEncoder ENCODER = BuffJson.encoder();
	private static final BuffJsonDecoder DECODER = BuffJson.decoder();
	private static final BuffJsonDecoder CODEGEN_DECODER = BuffJson.decoder();
	private static final BuffJsonDecoder RUNTIME_DECODER = BuffJson.decoder().setGeneratedDecoders(false);

	// =========================================================================
	// Malformed JSON syntax
	// =========================================================================

	@Nested
	class MalformedSyntax {

		@Test
		void emptyStringReturnsNull() {
			// fastjson2 returns null for empty input
			TestAllScalars msg = DECODER.decode("", TestAllScalars.class);
			assertNull(msg);
		}

		@Test
		void garbageTextThrows() {
			assertDecodeThrows("this is not json", TestAllScalars.class);
		}

		@Test
		void truncatedObjectParsesLeniently() {
			// fastjson2 accepts truncated objects — parses what it can
			TestAllScalars msg = DECODER.decode("{\"optionalInt32\": 42", TestAllScalars.class);
			assertEquals(42, msg.getOptionalInt32());
		}

		@Test
		void truncatedArrayThrows() {
			assertDecodeThrows("{\"repeatedInt32\": [1, 2", TestRepeatedScalars.class);
		}

		@Test
		void trailingCommaParsesLeniently() {
			// fastjson2 accepts trailing commas — lenient behavior
			TestAllScalars msg = DECODER.decode("{\"optionalInt32\": 42,}", TestAllScalars.class);
			assertEquals(42, msg.getOptionalInt32());
		}

		@Test
		void jsonArrayThrows() {
			assertDecodeThrows("[1, 2, 3]", TestAllScalars.class);
		}

		@Test
		void nullLiteralReturnsNull() {
			// fastjson2 returns null for JSON null literal
			TestAllScalars msg = DECODER.decode("null", TestAllScalars.class);
			assertNull(msg);
		}

		@Test
		void plainStringThrows() {
			assertDecodeThrows("\"hello\"", TestAllScalars.class);
		}

		@Test
		void plainNumberThrows() {
			assertDecodeThrows("42", TestAllScalars.class);
		}
	}

	// =========================================================================
	// Type mismatches
	// =========================================================================

	@Nested
	class TypeMismatch {

		@Test
		void stringForIntThrows() {
			assertDecodeThrows("{\"optionalInt32\": \"not-a-number\"}", TestAllScalars.class);
		}

		@Test
		void objectForStringCoercesLeniently() {
			// fastjson2 coerces objects to string (JSON-encodes the value) — lenient
			TestAllScalars msg = DECODER.decode("{\"optionalString\": {\"nested\": true}}", TestAllScalars.class);
			assertNotNull(msg.getOptionalString());
			assertFalse(msg.getOptionalString().isEmpty());
		}

		@Test
		void boolForIntCoercesLeniently() {
			// fastjson2 coerces bool to int (true→1, false→0) — lenient
			TestAllScalars msg = DECODER.decode("{\"optionalInt32\": true}", TestAllScalars.class);
			assertEquals(1, msg.getOptionalInt32());
		}

		@Test
		void arrayForScalarThrows() {
			assertDecodeThrows("{\"optionalInt32\": [1, 2, 3]}", TestAllScalars.class);
		}

		@Test
		void stringForMessageThrows() {
			assertDecodeThrows("{\"nested\": \"not-an-object\"}", TestNesting.class);
		}

		@Test
		void numberForMessageThrows() {
			assertDecodeThrows("{\"nested\": 42}", TestNesting.class);
		}
	}

	// =========================================================================
	// Invalid enum values
	// =========================================================================

	@Nested
	class InvalidEnum {

		@Test
		void unknownNameThrowsWithValue() {
			var ex = assertDecodeThrows("{\"enumValue\": \"DOES_NOT_EXIST\"}", TestNesting.class);
			assertTrue(ex.getMessage().contains("DOES_NOT_EXIST") || hasCauseContaining(ex, "DOES_NOT_EXIST"),
					"Error should mention the invalid enum value, got: " + deepMessage(ex));
		}

		@Test
		void boolForEnumCoercesLeniently() {
			// fastjson2 coerces bool to int, then looks up enum by number — lenient
			// true→1 maps to TEST_ENUM_FOO (number 1)
			TestNesting msg = DECODER.decode("{\"enumValue\": true}", TestNesting.class);
			assertEquals(TestEnum.TEST_ENUM_FOO, msg.getEnumValue());
		}

		@Test
		void floatForEnumTruncatesLeniently() {
			// fastjson2 truncates float to int for enum lookup — lenient
			// 1.5→1 maps to TEST_ENUM_FOO (number 1)
			TestNesting msg = DECODER.decode("{\"enumValue\": 1.5}", TestNesting.class);
			assertEquals(TestEnum.TEST_ENUM_FOO, msg.getEnumValue());
		}
	}

	// =========================================================================
	// Invalid well-known types
	// =========================================================================

	@Nested
	class InvalidWellKnownType {

		@Test
		void timestampBadFormatThrows() {
			assertDecodeThrows("{\"value\": \"not-a-timestamp\"}", TestTimestamp.class);
		}

		@Test
		void timestampAsNumberThrows() {
			assertDecodeThrows("{\"value\": 1704067200}", TestTimestamp.class);
		}

		@Test
		void durationMissingSuffixThrows() {
			var ex = assertDecodeThrows("{\"value\": \"3600\"}", TestDuration.class);
			assertTrue(ex.getMessage().contains("Invalid duration") || hasCauseContaining(ex, "Invalid duration"),
					"Error should mention invalid duration, got: " + deepMessage(ex));
		}

		@Test
		void durationWrongUnitThrows() {
			// "100mx" doesn't end with 's' — "Invalid duration"
			assertDecodeThrows("{\"value\": \"100mx\"}", TestDuration.class);
		}

		@Test
		void durationMsSuffixThrows() {
			// "100ms" ends with 's' so parseDuration strips it, tries to parse "100m"
			assertDecodeThrows("{\"value\": \"100ms\"}", TestDuration.class);
		}

		@Test
		void durationAsNumberThrows() {
			assertDecodeThrows("{\"value\": 3600}", TestDuration.class);
		}

		@Test
		void fieldMaskAsNumberCoercesLeniently() {
			// fastjson2 coerces number→string — lenient
			TestFieldMask msg = DECODER.decode("{\"value\": 42}", TestFieldMask.class);
			assertNotNull(msg.getValue());
		}

		@Test
		void invalidBase64BytesThrows() {
			assertDecodeThrows("{\"optionalBytes\": \"not-valid-base64!!!\"}", TestAllScalars.class);
		}
	}

	// =========================================================================
	// Invalid int64/uint64 strings
	// =========================================================================

	@Nested
	class InvalidNumericString {

		@Test
		void int64NonNumericThrows() {
			assertDecodeThrows("{\"optionalInt64\": \"abc\"}", TestAllScalars.class);
		}

		@Test
		void int64OverflowThrows() {
			assertDecodeThrows("{\"optionalInt64\": \"99999999999999999999\"}", TestAllScalars.class);
		}

		@Test
		void int64FloatStringThrows() {
			assertDecodeThrows("{\"optionalInt64\": \"1.5\"}", TestAllScalars.class);
		}

		@Test
		void uint64NonNumericThrows() {
			assertDecodeThrows("{\"optionalUint64\": \"xyz\"}", TestAllScalars.class);
		}
	}

	// =========================================================================
	// Invalid float/double values
	// =========================================================================

	@Nested
	class InvalidFloat {

		@Test
		void badSpecialStringThrows() {
			// Only "NaN", "Infinity", "-Infinity" are valid special strings
			assertDecodeThrows("{\"optionalFloat\": \"NotANumber\"}", TestAllScalars.class);
		}

		@Test
		void objectForFloatCoercesLeniently() {
			// fastjson2 coerces empty object to 0.0 — lenient
			TestAllScalars msg = DECODER.decode("{\"optionalFloat\": {}}", TestAllScalars.class);
			assertEquals(0.0f, msg.getOptionalFloat());
		}
	}

	// =========================================================================
	// Invalid map fields
	// =========================================================================

	@Nested
	class InvalidMap {

		@Test
		void arrayForMapThrows() {
			assertDecodeThrows("{\"stringToString\": [\"a\", \"b\"]}", TestMaps.class);
		}

		@Test
		void nonNumericIntKeyThrows() {
			assertDecodeThrows("{\"int32ToString\": {\"not-a-number\": \"value\"}}", TestMaps.class);
		}
	}

	// =========================================================================
	// Any type errors
	// =========================================================================

	@Nested
	class AnyError {

		@Test
		void decodeWithoutRegistryThrows() {
			String json = "{\"value\":{\"@type\":\"type.googleapis.com/io.suboptimal.buffjson.proto.NestedMessage\",\"value\":1}}";
			var ex = assertThrows(Exception.class, () -> DECODER.decode(json, TestAny.class));
			assertTrue(ex.getMessage().contains("TypeRegistry") || hasCauseContaining(ex, "TypeRegistry"),
					"Error should mention TypeRegistry, got: " + deepMessage(ex));
		}

		@Test
		void decodeUnregisteredTypeThrows() {
			var registry = TypeRegistry.newBuilder().add(TestAllScalars.getDescriptor()).build();
			var decoder = BuffJson.decoder().setTypeRegistry(registry);
			String json = "{\"value\":{\"@type\":\"type.googleapis.com/some.unknown.Type\",\"value\":1}}";
			var ex = assertThrows(Exception.class, () -> decoder.decode(json, TestAny.class));
			assertTrue(ex.getMessage().contains("Cannot find type") || hasCauseContaining(ex, "Cannot find type"),
					"Error should mention unknown type, got: " + deepMessage(ex));
		}

		@Test
		void encodeWithoutRegistryThrows() {
			var nested = NestedMessage.newBuilder().setValue(1).setName("test").build();
			var any = Any.pack(nested);
			var msg = TestAny.newBuilder().setValue(any).build();

			var ex = assertThrows(Exception.class, () -> ENCODER.encode(msg));
			assertTrue(ex.getMessage().contains("TypeRegistry") || hasCauseContaining(ex, "TypeRegistry"),
					"Error should mention TypeRegistry, got: " + deepMessage(ex));
		}
	}

	// =========================================================================
	// Graceful handling: unknown fields and partial messages
	// =========================================================================

	@Nested
	class GracefulHandling {

		@Test
		void unknownFieldsIgnored() {
			String json = "{\"optionalInt32\": 42, \"unknownField\": \"ignored\", \"anotherUnknown\": 999}";
			TestAllScalars msg = DECODER.decode(json, TestAllScalars.class);
			assertEquals(42, msg.getOptionalInt32());
		}

		@Test
		void emptyObjectProducesDefault() {
			TestAllScalars msg = DECODER.decode("{}", TestAllScalars.class);
			assertEquals(TestAllScalars.getDefaultInstance(), msg);
		}

		@Test
		void partialFieldsRestAreDefaults() {
			String json = "{\"optionalString\": \"hello\"}";
			TestAllScalars msg = DECODER.decode(json, TestAllScalars.class);
			assertEquals("hello", msg.getOptionalString());
			assertEquals(0, msg.getOptionalInt32());
			assertEquals(false, msg.getOptionalBool());
		}

		@Test
		void emptyRepeatedProducesEmptyList() {
			String json = "{\"repeatedInt32\": []}";
			TestRepeatedScalars msg = DECODER.decode(json, TestRepeatedScalars.class);
			assertEquals(0, msg.getRepeatedInt32Count());
		}

		@Test
		void emptyMapProducesEmptyMap() {
			String json = "{\"stringToString\": {}}";
			TestMaps msg = DECODER.decode(json, TestMaps.class);
			assertEquals(0, msg.getStringToStringCount());
		}

		@Test
		void emptyNestedMessageProducesDefaults() {
			String json = "{\"nested\": {}}";
			TestNesting msg = DECODER.decode(json, TestNesting.class);
			assertNotNull(msg.getNested());
			assertEquals(0, msg.getNested().getValue());
			assertEquals("", msg.getNested().getName());
		}

		@Test
		void nullFieldValuesTreatedAsDefaults() {
			String json = "{\"optionalInt32\": null, \"optionalString\": null}";
			TestAllScalars msg = DECODER.decode(json, TestAllScalars.class);
			assertEquals(0, msg.getOptionalInt32());
			assertEquals("", msg.getOptionalString());
		}
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	/**
	 * Asserts that decoding the given JSON throws an exception on both codegen and
	 * runtime paths. Returns the exception from the runtime path for further
	 * assertion.
	 */
	private static <T extends Message> Exception assertDecodeThrows(String json, Class<T> clazz) {
		assertThrows(Exception.class, () -> CODEGEN_DECODER.decode(json, clazz),
				"Codegen decoder should throw for: " + json);
		return assertThrows(Exception.class, () -> RUNTIME_DECODER.decode(json, clazz),
				"Runtime decoder should throw for: " + json);
	}

	/** Walks the cause chain looking for a message containing the given text. */
	private static boolean hasCauseContaining(Throwable ex, String text) {
		Throwable current = ex;
		while (current != null) {
			if (current.getMessage() != null && current.getMessage().contains(text)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	/** Collects all messages in the cause chain for diagnostic output. */
	private static String deepMessage(Throwable ex) {
		StringBuilder sb = new StringBuilder();
		Throwable current = ex;
		while (current != null) {
			if (sb.length() > 0)
				sb.append(" -> ");
			sb.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
			current = current.getCause();
		}
		return sb.toString();
	}
}
