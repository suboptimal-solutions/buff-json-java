package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.protobuf.*;
import com.google.protobuf.util.JsonFormat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.jackson.BuffJsonJacksonModule;
import io.suboptimal.buffjson.proto.*;

/**
 * Jackson integration tests using the shared conformance proto messages.
 *
 * <p>
 * Verifies encode/decode through Jackson's ObjectMapper under different
 * conditions: direct proto messages, proto inside records, proto inside
 * collections, String vs byte[] input, and error cases.
 */
class BuffJsonJacksonTest {

	private static final ObjectMapper MAPPER = JsonMapper.builder().enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
			.addModule(new BuffJsonJacksonModule()).build();

	private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();

	// -- Test DTOs --

	record Envelope<T>(String type, T payload) {
	}

	record ScalarEnvelope(String type, TestAllScalars payload) {
	}

	record NestingEnvelope(String type, TestNesting payload) {
	}

	record NestedEnvelope(String id, ScalarEnvelope inner) {
	}

	record ListEnvelope(String name, List<NestedMessage> items) {
	}

	record MapEnvelope(Map<String, NestedMessage> entries) {
	}

	record MultiProtoRecord(NestedMessage first, TestAllScalars second) {
	}

	// =========================================================================
	// Encode conformance: Jackson output must match JsonFormat.printer()
	// =========================================================================
	@Nested
	class EncodeConformance {

		private void assertConformance(Message message) throws Exception {
			String expected = PRINTER.print(message);
			String actual = MAPPER.writeValueAsString(message);
			assertEquals(expected, actual,
					"Jackson output must match JsonFormat for " + message.getDescriptorForType().getName());
		}

		@Test
		void scalars() throws Exception {
			assertConformance(TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalInt64(Long.MAX_VALUE)
					.setOptionalUint32(-1).setOptionalUint64(-1L).setOptionalFloat(3.14f).setOptionalDouble(2.718)
					.setOptionalBool(true).setOptionalString("hello world")
					.setOptionalBytes(ByteString.copyFromUtf8("binary")).build());
		}

		@Test
		void defaultValues() throws Exception {
			assertConformance(TestAllScalars.getDefaultInstance());
		}

		@Test
		void specialFloats() throws Exception {
			assertConformance(TestAllScalars.newBuilder().setOptionalFloat(Float.NaN)
					.setOptionalDouble(Double.POSITIVE_INFINITY).build());
		}

		@Test
		void nesting() throws Exception {
			assertConformance(
					TestNesting.newBuilder().setNested(NestedMessage.newBuilder().setValue(1).setName("nested"))
							.addRepeatedNested(NestedMessage.newBuilder().setValue(2).setName("a"))
							.addRepeatedNested(NestedMessage.newBuilder().setValue(3).setName("b"))
							.setEnumValue(TestEnum.TEST_ENUM_FOO).addRepeatedEnum(TestEnum.TEST_ENUM_BAR).build());
		}

		@Test
		void maps() throws Exception {
			assertConformance(TestMaps.newBuilder().putStringToString("k1", "v1").putStringToString("k2", "v2")
					.putStringToMessage("m", NestedMessage.newBuilder().setValue(10).setName("ten").build())
					.putInt32ToString(42, "answer").putBoolToString(true, "yes").build());
		}

		@Test
		void oneof() throws Exception {
			assertConformance(TestOneof.newBuilder().setName("test").setIntValue(99).build());
			assertConformance(TestOneof.newBuilder().setStringValue("chosen").build());
			assertConformance(TestOneof.newBuilder()
					.setMessageValue(NestedMessage.newBuilder().setValue(5).setName("five")).build());
		}

		@Test
		void timestamp() throws Exception {
			assertConformance(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1704067200).setNanos(500000000)).build());
		}

		@Test
		void duration() throws Exception {
			assertConformance(TestDuration.newBuilder()
					.setValue(Duration.newBuilder().setSeconds(3600).setNanos(500000000)).build());
		}

		@Test
		void fieldMask() throws Exception {
			assertConformance(TestFieldMask.newBuilder()
					.setValue(FieldMask.newBuilder().addPaths("foo_bar").addPaths("baz_qux")).build());
		}

		@Test
		void struct() throws Exception {
			assertConformance(
					TestStruct.newBuilder()
							.setStructValue(Struct.newBuilder()
									.putFields("key", Value.newBuilder().setStringValue("val").build())
									.putFields("num", Value.newBuilder().setNumberValue(42.0).build())
									.putFields("flag", Value.newBuilder().setBoolValue(true).build())
									.putFields("nil", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()))
							.setValue(Value.newBuilder().setStringValue("standalone")).build());
		}

		@Test
		void wrappers() throws Exception {
			assertConformance(TestWrappers.newBuilder().setInt32Value(Int32Value.of(42))
					.setUint32Value(UInt32Value.of(100)).setInt64Value(Int64Value.of(Long.MAX_VALUE))
					.setFloatValue(FloatValue.of(1.5f)).setDoubleValue(DoubleValue.of(2.5))
					.setBoolValue(BoolValue.of(true)).setStringValue(StringValue.of("wrapped"))
					.setBytesValue(BytesValue.of(ByteString.copyFromUtf8("bytes"))).build());
		}

		@Test
		void empty() throws Exception {
			assertConformance(TestEmpty.newBuilder().setValue(Empty.getDefaultInstance()).build());
		}

		@Test
		void optionalFields() throws Exception {
			assertConformance(TestOptionalFields.newBuilder().setOptionalInt32(0).setOptionalString("")
					.setOptionalBool(false).build());
		}

		@Test
		void customJsonName() throws Exception {
			assertConformance(TestCustomJsonName.newBuilder().setValue(42).setName("custom").build());
		}

		@Test
		void recursive() throws Exception {
			assertConformance(TestRecursive.newBuilder().setValue(1)
					.setChild(TestRecursive.newBuilder().setValue(2).setChild(TestRecursive.newBuilder().setValue(3)))
					.build());
		}

		@Test
		void anyType() throws Exception {
			var inner = NestedMessage.newBuilder().setValue(42).setName("packed").build();
			var any = Any.pack(inner);
			var msg = TestAny.newBuilder().setValue(any).build();

			var registry = TypeRegistry.newBuilder().add(NestedMessage.getDescriptor()).build();
			var mapperWithRegistry = JsonMapper.builder().enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
					.addModule(new BuffJsonJacksonModule(registry)).build();
			var printerWithRegistry = JsonFormat.printer().omittingInsignificantWhitespace()
					.usingTypeRegistry(registry);

			assertEquals(printerWithRegistry.print(msg), mapperWithRegistry.writeValueAsString(msg));
		}
	}

	// =========================================================================
	// Decode roundtrip: encode → decode → equals (String input)
	// =========================================================================
	@Nested
	class DecodeFromString {

		private <T extends Message> void assertRoundTrip(T original) throws Exception {
			String json = MAPPER.writeValueAsString(original);
			@SuppressWarnings("unchecked")
			Class<T> clazz = (Class<T>) original.getClass();
			T decoded = MAPPER.readValue(json, clazz);
			assertEquals(original, decoded);
		}

		@Test
		void scalars() throws Exception {
			assertRoundTrip(TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalInt64(123456789L)
					.setOptionalString("round trip").setOptionalBool(true)
					.setOptionalBytes(ByteString.copyFromUtf8("data")).setOptionalFloat(1.5f).setOptionalDouble(2.5)
					.build());
		}

		@Test
		void nesting() throws Exception {
			assertRoundTrip(TestNesting.newBuilder().setNested(NestedMessage.newBuilder().setValue(1).setName("n"))
					.addRepeatedNested(NestedMessage.newBuilder().setValue(2).setName("r"))
					.setEnumValue(TestEnum.TEST_ENUM_FOO).build());
		}

		@Test
		void maps() throws Exception {
			assertRoundTrip(TestMaps.newBuilder().putStringToString("k", "v").putInt32ToString(1, "one")
					.putBoolToString(true, "yes")
					.putStringToMessage("msg", NestedMessage.newBuilder().setValue(1).build()).build());
		}

		@Test
		void wellKnownTypes() throws Exception {
			assertRoundTrip(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1704067200).setNanos(123456789)).build());
			assertRoundTrip(TestDuration.newBuilder().setValue(Duration.newBuilder().setSeconds(60)).build());
			assertRoundTrip(TestFieldMask.newBuilder().setValue(FieldMask.newBuilder().addPaths("foo_bar")).build());
			assertRoundTrip(TestEmpty.newBuilder().setValue(Empty.getDefaultInstance()).build());
		}

		@Test
		void wrappers() throws Exception {
			assertRoundTrip(TestWrappers.newBuilder().setInt32Value(Int32Value.of(42))
					.setStringValue(StringValue.of("w")).setBoolValue(BoolValue.of(true)).build());
		}

		@Test
		void oneof() throws Exception {
			assertRoundTrip(TestOneof.newBuilder().setName("test").setIntValue(42).build());
			assertRoundTrip(TestOneof.newBuilder().setStringValue("hello").build());
		}

		@Test
		void optionalFields() throws Exception {
			assertRoundTrip(TestOptionalFields.newBuilder().setOptionalInt32(0).setOptionalString("")
					.setOptionalBool(false).build());
		}

		@Test
		void recursive() throws Exception {
			assertRoundTrip(
					TestRecursive.newBuilder().setValue(1).setChild(TestRecursive.newBuilder().setValue(2)).build());
		}
	}

	// =========================================================================
	// Decode roundtrip: encode → decode → equals (byte[] input)
	// =========================================================================
	@Nested
	class DecodeFromBytes {

		private <T extends Message> void assertRoundTrip(T original) throws Exception {
			byte[] jsonBytes = MAPPER.writeValueAsBytes(original);
			@SuppressWarnings("unchecked")
			Class<T> clazz = (Class<T>) original.getClass();
			T decoded = MAPPER.readValue(jsonBytes, clazz);
			assertEquals(original, decoded);
		}

		@Test
		void scalars() throws Exception {
			assertRoundTrip(TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalInt64(123456789L)
					.setOptionalString("round trip").setOptionalBool(true)
					.setOptionalBytes(ByteString.copyFromUtf8("data")).setOptionalFloat(1.5f).setOptionalDouble(2.5)
					.build());
		}

		@Test
		void nesting() throws Exception {
			assertRoundTrip(TestNesting.newBuilder().setNested(NestedMessage.newBuilder().setValue(1).setName("n"))
					.addRepeatedNested(NestedMessage.newBuilder().setValue(2).setName("r"))
					.setEnumValue(TestEnum.TEST_ENUM_FOO).build());
		}

		@Test
		void maps() throws Exception {
			assertRoundTrip(TestMaps.newBuilder().putStringToString("k", "v").putInt32ToString(1, "one")
					.putBoolToString(true, "yes")
					.putStringToMessage("msg", NestedMessage.newBuilder().setValue(1).build()).build());
		}

		@Test
		void wellKnownTypes() throws Exception {
			assertRoundTrip(TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(1704067200).setNanos(123456789)).build());
			assertRoundTrip(TestDuration.newBuilder().setValue(Duration.newBuilder().setSeconds(60)).build());
		}

		@Test
		void wrappers() throws Exception {
			assertRoundTrip(TestWrappers.newBuilder().setInt32Value(Int32Value.of(42))
					.setStringValue(StringValue.of("w")).setBoolValue(BoolValue.of(true)).build());
		}

		@Test
		void unicode() throws Exception {
			assertRoundTrip(
					TestAllScalars.newBuilder().setOptionalString("hello \u00e9\u00e8\u00ea \u4e16\u754c").build());
		}

		@Test
		void bytesField() throws Exception {
			assertRoundTrip(
					TestAllScalars.newBuilder().setOptionalBytes(ByteString.copyFromUtf8("binary data")).build());
		}
	}

	// =========================================================================
	// Proto inside records (one level of wrapping)
	// =========================================================================
	@Nested
	class ProtoInRecord {

		@Test
		void scalarEnvelopeFromString() throws Exception {
			var envelope = new ScalarEnvelope("scalars",
					TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalString("hello").build());
			String json = MAPPER.writeValueAsString(envelope);
			assertEquals(envelope, MAPPER.readValue(json, ScalarEnvelope.class));
		}

		@Test
		void scalarEnvelopeFromBytes() throws Exception {
			var envelope = new ScalarEnvelope("scalars",
					TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalString("hello").build());
			byte[] jsonBytes = MAPPER.writeValueAsBytes(envelope);
			assertEquals(envelope, MAPPER.readValue(jsonBytes, ScalarEnvelope.class));
		}

		@Test
		void nestingEnvelopeFromString() throws Exception {
			var envelope = new NestingEnvelope("nesting",
					TestNesting.newBuilder().setNested(NestedMessage.newBuilder().setValue(1).setName("n"))
							.setEnumValue(TestEnum.TEST_ENUM_FOO).build());
			String json = MAPPER.writeValueAsString(envelope);
			assertEquals(envelope, MAPPER.readValue(json, NestingEnvelope.class));
		}

		@Test
		void multipleProtoFields() throws Exception {
			var record = new MultiProtoRecord(NestedMessage.newBuilder().setValue(1).setName("first").build(),
					TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalBool(true).build());
			String json = MAPPER.writeValueAsString(record);
			assertEquals(record, MAPPER.readValue(json, MultiProtoRecord.class));
		}

		@Test
		void multipleProtoFieldsFromBytes() throws Exception {
			var record = new MultiProtoRecord(NestedMessage.newBuilder().setValue(1).setName("first").build(),
					TestAllScalars.newBuilder().setOptionalInt32(42).build());
			byte[] jsonBytes = MAPPER.writeValueAsBytes(record);
			assertEquals(record, MAPPER.readValue(jsonBytes, MultiProtoRecord.class));
		}

		@Test
		void nullProto() throws Exception {
			var envelope = new ScalarEnvelope("empty", null);
			String json = MAPPER.writeValueAsString(envelope);
			assertEquals(envelope, MAPPER.readValue(json, ScalarEnvelope.class));
		}

		@Test
		void defaultProto() throws Exception {
			var envelope = new ScalarEnvelope("default", TestAllScalars.getDefaultInstance());
			String json = MAPPER.writeValueAsString(envelope);
			assertEquals(envelope, MAPPER.readValue(json, ScalarEnvelope.class));
		}
	}

	// =========================================================================
	// Deeply nested (record inside record)
	// =========================================================================
	@Nested
	class DeeplyNested {

		@Test
		void recordInsideRecordFromString() throws Exception {
			var inner = new ScalarEnvelope("inner",
					TestAllScalars.newBuilder().setOptionalInt32(99).setOptionalString("deep").build());
			var outer = new NestedEnvelope("outer-id", inner);
			String json = MAPPER.writeValueAsString(outer);
			assertEquals(outer, MAPPER.readValue(json, NestedEnvelope.class));
		}

		@Test
		void recordInsideRecordFromBytes() throws Exception {
			var inner = new ScalarEnvelope("inner", TestAllScalars.newBuilder().setOptionalInt32(99).build());
			var outer = new NestedEnvelope("outer-id", inner);
			byte[] jsonBytes = MAPPER.writeValueAsBytes(outer);
			assertEquals(outer, MAPPER.readValue(jsonBytes, NestedEnvelope.class));
		}
	}

	// =========================================================================
	// Proto inside collections
	// =========================================================================
	@Nested
	class ProtoInCollections {

		@Test
		void listEnvelopeFromString() throws Exception {
			var envelope = new ListEnvelope("batch",
					List.of(NestedMessage.newBuilder().setValue(1).setName("a").build(),
							NestedMessage.newBuilder().setValue(2).setName("b").build(),
							NestedMessage.newBuilder().setValue(3).setName("c").build()));
			String json = MAPPER.writeValueAsString(envelope);
			assertEquals(envelope, MAPPER.readValue(json, ListEnvelope.class));
		}

		@Test
		void listEnvelopeFromBytes() throws Exception {
			var envelope = new ListEnvelope("batch",
					List.of(NestedMessage.newBuilder().setValue(1).setName("a").build(),
							NestedMessage.newBuilder().setValue(2).setName("b").build()));
			byte[] jsonBytes = MAPPER.writeValueAsBytes(envelope);
			assertEquals(envelope, MAPPER.readValue(jsonBytes, ListEnvelope.class));
		}

		@Test
		void mapEnvelopeFromString() throws Exception {
			var envelope = new MapEnvelope(Map.of("a", NestedMessage.newBuilder().setValue(1).setName("alice").build(),
					"b", NestedMessage.newBuilder().setValue(2).setName("bob").build()));
			String json = MAPPER.writeValueAsString(envelope);
			assertEquals(envelope, MAPPER.readValue(json, MapEnvelope.class));
		}

		@Test
		void mapEnvelopeFromBytes() throws Exception {
			var envelope = new MapEnvelope(Map.of("x", NestedMessage.newBuilder().setValue(10).setName("x").build()));
			byte[] jsonBytes = MAPPER.writeValueAsBytes(envelope);
			assertEquals(envelope, MAPPER.readValue(jsonBytes, MapEnvelope.class));
		}

		@Test
		void topLevelListFromString() throws Exception {
			var list = List.of(NestedMessage.newBuilder().setValue(1).setName("one").build(),
					NestedMessage.newBuilder().setValue(2).setName("two").build());
			String json = MAPPER.writeValueAsString(list);
			assertEquals(list, MAPPER.readValue(json, new TypeReference<List<NestedMessage>>() {
			}));
		}

		@Test
		void topLevelListFromBytes() throws Exception {
			var list = List.of(NestedMessage.newBuilder().setValue(1).setName("one").build(),
					NestedMessage.newBuilder().setValue(2).setName("two").build());
			byte[] jsonBytes = MAPPER.writeValueAsBytes(list);
			assertEquals(list, MAPPER.readValue(jsonBytes, new TypeReference<List<NestedMessage>>() {
			}));
		}
	}

	// =========================================================================
	// Cross-library compatibility: Jackson ↔ BuffJson
	// =========================================================================
	@Nested
	class CrossLibraryCompatibility {

		private static final BuffJsonEncoder ENCODER = BuffJson.encoder();
		private static final BuffJsonDecoder DECODER = BuffJson.decoder();

		@Test
		void jacksonEncodeBuffJsonDecode() throws Exception {
			var original = TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalString("cross")
					.setOptionalBool(true).build();

			String jacksonJson = MAPPER.writeValueAsString(original);
			TestAllScalars fromBuffJson = DECODER.decode(jacksonJson, TestAllScalars.class);
			assertEquals(original, fromBuffJson);
		}

		@Test
		void buffJsonEncodeJacksonDecode() throws Exception {
			var original = TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalString("cross")
					.setOptionalBool(true).build();

			String buffJson = ENCODER.encode(original);
			TestAllScalars fromJackson = MAPPER.readValue(buffJson, TestAllScalars.class);
			assertEquals(original, fromJackson);
		}

		@Test
		void outputsMatch() throws Exception {
			var original = TestAllScalars.newBuilder().setOptionalInt32(42).setOptionalString("match").build();
			assertEquals(ENCODER.encode(original), MAPPER.writeValueAsString(original));
		}
	}

	// =========================================================================
	// Special inputs
	// =========================================================================
	@Nested
	class SpecialInputs {

		@Test
		void externalJsonString() throws Exception {
			String json = "{\"optionalInt32\":42,\"optionalString\":\"external\",\"optionalBool\":true}";
			TestAllScalars decoded = MAPPER.readValue(json, TestAllScalars.class);
			assertEquals(42, decoded.getOptionalInt32());
			assertEquals("external", decoded.getOptionalString());
			assertTrue(decoded.getOptionalBool());
		}

		@Test
		void externalJsonBytes() throws Exception {
			byte[] json = "{\"optionalInt32\":42,\"optionalString\":\"external\"}".getBytes(StandardCharsets.UTF_8);
			TestAllScalars decoded = MAPPER.readValue(json, TestAllScalars.class);
			assertEquals(42, decoded.getOptionalInt32());
			assertEquals("external", decoded.getOptionalString());
		}

		@Test
		void jsonWithWhitespace() throws Exception {
			byte[] json = "  {  \"optionalInt32\" :  42  ,  \"optionalString\" : \"spaced\"  }  "
					.getBytes(StandardCharsets.UTF_8);
			TestAllScalars decoded = MAPPER.readValue(json, TestAllScalars.class);
			assertEquals(42, decoded.getOptionalInt32());
			assertEquals("spaced", decoded.getOptionalString());
		}

		@Test
		void unicodeFromString() throws Exception {
			var msg = TestAllScalars.newBuilder().setOptionalString("hello \u00e9\u00e8\u00ea \u4e16\u754c").build();
			String json = MAPPER.writeValueAsString(msg);
			assertEquals(msg, MAPPER.readValue(json, TestAllScalars.class));
		}

		@Test
		void unicodeFromBytes() throws Exception {
			var msg = TestAllScalars.newBuilder().setOptionalString("hello \u00e9\u00e8\u00ea \u4e16\u754c").build();
			byte[] jsonBytes = MAPPER.writeValueAsBytes(msg);
			assertEquals(msg, MAPPER.readValue(jsonBytes, TestAllScalars.class));
		}
	}

	// =========================================================================
	// Error conditions
	// =========================================================================
	@Nested
	class ErrorConditions {

		@Test
		void missingFeatureThrowsOnStringDecode() {
			ObjectMapper noFeature = new ObjectMapper().registerModule(new BuffJsonJacksonModule());
			var ex = assertThrows(Exception.class,
					() -> noFeature.readValue("{\"optionalInt32\":42}", TestAllScalars.class));
			assertTrue(ex.getMessage().contains("INCLUDE_SOURCE_IN_LOCATION"),
					"Error should mention required feature, got: " + ex.getMessage());
		}

		@Test
		void missingFeatureThrowsOnStreamDecode() {
			ObjectMapper noFeature = new ObjectMapper().registerModule(new BuffJsonJacksonModule());
			byte[] json = "{\"optionalInt32\":42}".getBytes(StandardCharsets.UTF_8);
			var ex = assertThrows(Exception.class,
					() -> noFeature.readValue(new ByteArrayInputStream(json), TestAllScalars.class));
			assertTrue(ex.getMessage().contains("INCLUDE_SOURCE_IN_LOCATION"),
					"Error should mention required feature, got: " + ex.getMessage());
		}

		@Test
		void encodeWorksWithoutFeature() throws Exception {
			ObjectMapper noFeature = new ObjectMapper().registerModule(new BuffJsonJacksonModule());
			var msg = TestAllScalars.newBuilder().setOptionalInt32(42).build();
			String json = noFeature.writeValueAsString(msg);
			assertTrue(json.contains("\"optionalInt32\":42"));
		}

		@Test
		void malformedJsonThrows() {
			String malformed = "{\"optionalInt32\": \"not-a-number\"}";
			var ex = assertThrows(Exception.class, () -> MAPPER.readValue(malformed, TestAllScalars.class));
			Throwable cause = ex;
			boolean found = false;
			while (cause != null) {
				if (cause.getMessage() != null && cause.getMessage().contains("Failed to decode protobuf")) {
					found = true;
					break;
				}
				cause = cause.getCause();
			}
			assertTrue(found, "Exception chain should contain 'Failed to decode protobuf'");
		}
	}
}
