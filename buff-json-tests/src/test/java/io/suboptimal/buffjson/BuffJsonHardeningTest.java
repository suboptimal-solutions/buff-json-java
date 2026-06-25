package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson2.JSONException;
import com.google.protobuf.*;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.proto.*;

/**
 * Decoder hardening tests for untrusted JSON input:
 *
 * <ul>
 * <li><b>Recursion depth cap</b> — deeply nested {@code Struct}/{@code Value}/
 * {@code ListValue} input fails with a clean {@link JSONException} instead of a
 * {@code StackOverflowError}. Matches protobuf's own recursion limit of 100.
 * <li><b>Normalized parse errors</b> — malformed numbers/timestamps/durations
 * surface as a single {@link JSONException} type carrying the JSON offset (via
 * {@code JSONReader.info}), rather than a grab-bag of
 * {@code NumberFormatException} / {@code DateTimeParseException}.
 * <li><b>Any field-order independence</b> — the {@code @type}-first fast path
 * and the {@code @type}-after-content slow path decode identically.
 * </ul>
 *
 * <p>
 * Each behavior is checked on both the codegen and runtime decoder paths where
 * applicable (both route scalar/WKT/Struct reads through the same helpers).
 */
class BuffJsonHardeningTest {

	private static final BuffJsonDecoder CODEGEN_DECODER = BuffJson.decoder();
	private static final BuffJsonDecoder RUNTIME_DECODER = BuffJson.decoder().setGeneratedDecoders(false);

	// =========================================================================
	// Recursion depth cap (StackOverflow protection)
	// =========================================================================

	@Nested
	class RecursionDepth {

		private String nestedStruct(int depth) {
			return "{\"structValue\":" + "{\"a\":".repeat(depth) + "1" + "}".repeat(depth) + "}";
		}

		private String nestedList(int depth) {
			return "{\"listValue\":" + "[".repeat(depth) + "]".repeat(depth) + "}";
		}

		@Test
		void deeplyNestedStructThrowsCleanly() {
			String json = nestedStruct(500);
			// Must be a clean JSONException, NOT a StackOverflowError.
			JSONException ex = assertThrows(JSONException.class, () -> CODEGEN_DECODER.decode(json, TestStruct.class));
			assertTrue(ex.getMessage().contains("nesting depth"),
					"Expected nesting-depth message, got: " + ex.getMessage());
			assertThrows(JSONException.class, () -> RUNTIME_DECODER.decode(json, TestStruct.class));
		}

		@Test
		void deeplyNestedListValueThrowsCleanly() {
			String json = nestedList(500);
			JSONException ex = assertThrows(JSONException.class, () -> CODEGEN_DECODER.decode(json, TestStruct.class));
			assertTrue(ex.getMessage().contains("nesting depth"),
					"Expected nesting-depth message, got: " + ex.getMessage());
			assertThrows(JSONException.class, () -> RUNTIME_DECODER.decode(json, TestStruct.class));
		}

		@Test
		void deeplyNestedValueThrowsCleanly() {
			// google.protobuf.Value dispatches into the same Struct recursion.
			String json = "{\"value\":" + "{\"a\":".repeat(500) + "1" + "}".repeat(500) + "}";
			assertThrows(JSONException.class, () -> CODEGEN_DECODER.decode(json, TestStruct.class));
		}

		@Test
		void moderatelyNestedStructDecodes() {
			// Well under the limit — must still decode successfully on both paths.
			String json = nestedStruct(50);
			TestStruct codegen = CODEGEN_DECODER.decode(json, TestStruct.class);
			assertTrue(codegen.hasStructValue());
			TestStruct runtime = RUNTIME_DECODER.decode(json, TestStruct.class);
			assertEquals(codegen, runtime);
		}
	}

	// =========================================================================
	// Normalized parse errors carrying the JSON offset
	// =========================================================================

	@Nested
	class ParseErrorOffset {

		private void assertJsonExceptionWithOffset(BuffJsonDecoder decoder, String json, Class<? extends Message> clazz,
				String hint) {
			JSONException ex = assertThrows(JSONException.class, () -> decoder.decode(json, clazz));
			assertTrue(ex.getMessage().contains("offset"), "Expected JSON offset in message, got: " + ex.getMessage());
			assertTrue(ex.getMessage().contains(hint),
					"Expected hint '" + hint + "' in message, got: " + ex.getMessage());
		}

		@Test
		void invalidInt64() {
			String json = "{\"optionalInt64\": \"not-a-number\"}";
			assertJsonExceptionWithOffset(CODEGEN_DECODER, json, TestAllScalars.class, "int64");
			assertJsonExceptionWithOffset(RUNTIME_DECODER, json, TestAllScalars.class, "int64");
		}

		@Test
		void invalidUint64() {
			String json = "{\"optionalUint64\": \"xyz\"}";
			assertJsonExceptionWithOffset(CODEGEN_DECODER, json, TestAllScalars.class, "uint64");
			assertJsonExceptionWithOffset(RUNTIME_DECODER, json, TestAllScalars.class, "uint64");
		}

		@Test
		void invalidDouble() {
			String json = "{\"optionalDouble\": \"NotANumber\"}";
			assertJsonExceptionWithOffset(CODEGEN_DECODER, json, TestAllScalars.class, "double");
			assertJsonExceptionWithOffset(RUNTIME_DECODER, json, TestAllScalars.class, "double");
		}

		@Test
		void invalidFloat() {
			String json = "{\"optionalFloat\": \"NotANumber\"}";
			assertJsonExceptionWithOffset(CODEGEN_DECODER, json, TestAllScalars.class, "float");
			assertJsonExceptionWithOffset(RUNTIME_DECODER, json, TestAllScalars.class, "float");
		}

		@Test
		void invalidTimestamp() {
			String json = "{\"value\": \"not-a-timestamp\"}";
			assertJsonExceptionWithOffset(CODEGEN_DECODER, json, TestTimestamp.class, "timestamp");
			assertJsonExceptionWithOffset(RUNTIME_DECODER, json, TestTimestamp.class, "timestamp");
		}

		@Test
		void invalidDuration() {
			String json = "{\"value\": \"3600\"}";
			assertJsonExceptionWithOffset(CODEGEN_DECODER, json, TestDuration.class, "duration");
			assertJsonExceptionWithOffset(RUNTIME_DECODER, json, TestDuration.class, "duration");
		}

		@Test
		void invalidIntMapKey() {
			// Map keys go through FieldReader on the runtime path.
			String json = "{\"int32ToString\": {\"not-a-number\": \"value\"}}";
			assertJsonExceptionWithOffset(RUNTIME_DECODER, json, TestMaps.class, "map key");
		}
	}

	// =========================================================================
	// Any: @type-first (fast path) vs @type-after-content (slow path) parity
	// =========================================================================

	@Nested
	class AnyFieldOrder {

		private static final TypeRegistry REGISTRY = TypeRegistry.newBuilder().add(TestAllScalars.getDescriptor())
				.add(Timestamp.getDescriptor()).add(TestAny.getDescriptor()).build();

		// Separate decoder instances — setGeneratedDecoders mutates and returns this.
		private static final BuffJsonDecoder CODEGEN = BuffJson.decoder().setTypeRegistry(REGISTRY);
		private static final BuffJsonDecoder RUNTIME = BuffJson.decoder().setTypeRegistry(REGISTRY)
				.setGeneratedDecoders(false);

		private void assertBothPaths(String json, TestAny expected) {
			assertEquals(expected, CODEGEN.decode(json, TestAny.class), "codegen, json=" + json);
			assertEquals(expected, RUNTIME.decode(json, TestAny.class), "runtime, json=" + json);
		}

		@Test
		void regularMessageTypeFirstAndLastAgree() {
			TestAllScalars inner = TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalString("hi").build();
			String typeUrl = Any.pack(inner).getTypeUrl();
			TestAny expected = TestAny.newBuilder().setValue(Any.pack(inner)).build();

			String fast = "{\"value\":{\"@type\":\"" + typeUrl + "\",\"optionalInt32\":42,\"optionalString\":\"hi\"}}";
			String slow = "{\"value\":{\"optionalInt32\":42,\"optionalString\":\"hi\",\"@type\":\"" + typeUrl + "\"}}";
			String mid = "{\"value\":{\"optionalInt32\":42,\"@type\":\"" + typeUrl + "\",\"optionalString\":\"hi\"}}";

			assertBothPaths(fast, expected);
			assertBothPaths(slow, expected);
			assertBothPaths(mid, expected);
		}

		@Test
		void wktTypeFirstAndLastAgree() {
			Timestamp ts = Timestamp.newBuilder().setSeconds(1704067200L).build(); // 2024-01-01T00:00:00Z
			String typeUrl = Any.pack(ts).getTypeUrl();
			TestAny expected = TestAny.newBuilder().setValue(Any.pack(ts)).build();

			String fast = "{\"value\":{\"@type\":\"" + typeUrl + "\",\"value\":\"2024-01-01T00:00:00Z\"}}";
			String slow = "{\"value\":{\"value\":\"2024-01-01T00:00:00Z\",\"@type\":\"" + typeUrl + "\"}}";

			assertBothPaths(fast, expected);
			assertBothPaths(slow, expected);
		}

		@Test
		void emptyTypeUrlFirstRejected() {
			// Fast path: @type present but empty, with trailing content. A non-empty Any
			// object needs a resolvable @type, so this is rejected on both paths (it was
			// previously accepted as a default Any). Conformance:
			// Required.Proto3.JsonInput.AnyWktRepresentationWithEmptyTypeAndValue.
			String json = "{\"value\":{\"@type\":\"\",\"optionalInt32\":42}}";
			assertThrows(JSONException.class, () -> CODEGEN.decode(json, TestAny.class), "codegen, json=" + json);
			assertThrows(JSONException.class, () -> RUNTIME.decode(json, TestAny.class), "runtime, json=" + json);
		}

		@Test
		void missingTypeUrlWithContentRejected() {
			// Slow path: content present but @type entirely absent — unresolvable, so
			// rejected on both paths (mirrors protobuf's "Missing type url").
			String json = "{\"value\":{\"optionalInt32\":42}}";
			assertThrows(JSONException.class, () -> CODEGEN.decode(json, TestAny.class), "codegen, json=" + json);
			assertThrows(JSONException.class, () -> RUNTIME.decode(json, TestAny.class), "runtime, json=" + json);
		}
	}

	// =========================================================================
	// Native fastjson2 error type: encode/decode failures throw JSONException
	// (not IllegalArgumentException / IllegalStateException /
	// NumberFormatException)
	// =========================================================================

	@Nested
	class NativeErrorType {

		private void assertDecodeThrowsJson(String json, Class<? extends Message> clazz) {
			assertThrows(JSONException.class, () -> CODEGEN_DECODER.decode(json, clazz),
					"codegen should throw JSONException for: " + json);
			assertThrows(JSONException.class, () -> RUNTIME_DECODER.decode(json, clazz),
					"runtime should throw JSONException for: " + json);
		}

		@Test
		void invalidBase64() {
			assertDecodeThrowsJson("{\"optionalBytes\": \"not-valid-base64!!!\"}", TestAllScalars.class);
		}

		@Test
		void unknownEnumName() {
			assertDecodeThrowsJson("{\"enumValue\": \"DOES_NOT_EXIST\"}", TestNesting.class);
		}

		@Test
		void invalidNumericMapKey() {
			// Now normalized on both codegen and runtime paths.
			assertDecodeThrowsJson("{\"int32ToString\": {\"not-a-number\": \"v\"}}", TestMaps.class);
		}

		@Test
		void anyDecodeWithoutRegistry() {
			// Missing TypeRegistry is a server-side config error, not bad input.
			String json = "{\"value\":{\"@type\":\"type.googleapis.com/io.suboptimal.buffjson.proto.NestedMessage\","
					+ "\"value\":1}}";
			assertThrows(IllegalStateException.class, () -> BuffJson.decoder().decode(json, TestAny.class));
		}

		@Test
		void anyDecodeUnregisteredType() {
			// Client submitted an @type the (configured) registry doesn't know —
			// user-facing,
			// so JSONException with the JSON offset.
			var decoder = BuffJson.decoder()
					.setTypeRegistry(TypeRegistry.newBuilder().add(TestAllScalars.getDescriptor()).build());
			String json = "{\"value\":{\"@type\":\"type.googleapis.com/some.unknown.Type\",\"value\":1}}";
			JSONException ex = assertThrows(JSONException.class, () -> decoder.decode(json, TestAny.class));
			assertTrue(ex.getMessage().contains("offset"), "Expected JSON offset in message, got: " + ex.getMessage());
		}

		@Test
		void anyEncodeWithoutRegistry() {
			// Missing TypeRegistry on the encoder is a server-side config error.
			var any = Any.pack(NestedMessage.newBuilder().setValue(1).setName("x").build());
			var msg = TestAny.newBuilder().setValue(any).build();
			assertThrows(IllegalStateException.class, () -> BuffJson.encoder().encode(msg));
		}
	}

	// =========================================================================
	// Any-packed WKT with null/missing "value" must not NullPointerException
	// =========================================================================

	@Nested
	class PackedWktNullValue {

		private static final TypeRegistry REGISTRY = TypeRegistry.newBuilder().add(Timestamp.getDescriptor())
				.add(BytesValue.getDescriptor()).add(Value.getDescriptor()).add(TestAny.getDescriptor()).build();
		private static final BuffJsonDecoder CODEGEN = BuffJson.decoder().setTypeRegistry(REGISTRY);
		private static final BuffJsonDecoder RUNTIME = BuffJson.decoder().setTypeRegistry(REGISTRY)
				.setGeneratedDecoders(false);

		private void assertDecodes(String json, TestAny expected) {
			assertEquals(expected, CODEGEN.decode(json, TestAny.class), "codegen, json=" + json);
			assertEquals(expected, RUNTIME.decode(json, TestAny.class), "runtime, json=" + json);
		}

		@Test
		void timestampNullValueDecodesToDefaultBothOrders() {
			String url = Any.pack(Timestamp.getDefaultInstance()).getTypeUrl();
			TestAny expected = TestAny.newBuilder().setValue(Any.pack(Timestamp.getDefaultInstance())).build();
			// fast path (@type first) and slow path (@type last) — neither may NPE
			assertDecodes("{\"value\":{\"@type\":\"" + url + "\",\"value\":null}}", expected);
			assertDecodes("{\"value\":{\"value\":null,\"@type\":\"" + url + "\"}}", expected);
		}

		@Test
		void timestampMissingValueDecodesToDefault() {
			String url = Any.pack(Timestamp.getDefaultInstance()).getTypeUrl();
			TestAny expected = TestAny.newBuilder().setValue(Any.pack(Timestamp.getDefaultInstance())).build();
			assertDecodes("{\"value\":{\"@type\":\"" + url + "\"}}", expected);
		}

		@Test
		void bytesValueNullValueDecodesToDefault() {
			BytesValue def = BytesValue.getDefaultInstance();
			String url = Any.pack(def).getTypeUrl();
			TestAny expected = TestAny.newBuilder().setValue(Any.pack(def)).build();
			assertDecodes("{\"value\":{\"@type\":\"" + url + "\",\"value\":null}}", expected);
		}

		@Test
		void valueNullBecomesNullValue() {
			// google.protobuf.Value with explicit null → NullValue (mirrors the field
			// path).
			Value nullVal = Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
			String url = Any.pack(Value.getDefaultInstance()).getTypeUrl();
			TestAny expected = TestAny.newBuilder().setValue(Any.pack(nullVal)).build();
			assertDecodes("{\"value\":{\"@type\":\"" + url + "\",\"value\":null}}", expected);
			assertDecodes("{\"value\":{\"value\":null,\"@type\":\"" + url + "\"}}", expected);
		}
	}

	// =========================================================================
	// Bool map keys: only "true"/"false" accepted; bad keys throw (no silent
	// coerce)
	// =========================================================================

	@Nested
	class BoolMapKey {

		@Test
		void validBoolKeysDecodeOnBothPaths() {
			String json = "{\"boolToString\":{\"true\":\"t\",\"false\":\"f\"}}";
			TestMaps codegen = CODEGEN_DECODER.decode(json, TestMaps.class);
			assertEquals("t", codegen.getBoolToStringMap().get(true));
			assertEquals("f", codegen.getBoolToStringMap().get(false));
			assertEquals(codegen, RUNTIME_DECODER.decode(json, TestMaps.class));
		}

		@Test
		void invalidBoolKeyThrowsBothPaths() {
			// Was silently coerced to false (data loss); now a clean JSONException.
			String json = "{\"boolToString\":{\"garbage\":\"v\"}}";
			assertThrows(JSONException.class, () -> CODEGEN_DECODER.decode(json, TestMaps.class));
			assertThrows(JSONException.class, () -> RUNTIME_DECODER.decode(json, TestMaps.class));
		}

		@Test
		void wrongCaseBoolKeyThrowsBothPaths() {
			// "TRUE" was silently accepted as true; proto3 keys are lowercase.
			String json = "{\"boolToString\":{\"TRUE\":\"v\"}}";
			assertThrows(JSONException.class, () -> CODEGEN_DECODER.decode(json, TestMaps.class));
			assertThrows(JSONException.class, () -> RUNTIME_DECODER.decode(json, TestMaps.class));
		}
	}
}
