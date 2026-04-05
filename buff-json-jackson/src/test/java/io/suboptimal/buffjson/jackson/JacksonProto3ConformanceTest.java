package io.suboptimal.buffjson.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.*;
import com.google.protobuf.util.JsonFormat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.jackson.proto.*;

/**
 * Proto3 JSON conformance tests for the Jackson module.
 *
 * <p>
 * Each test encodes a protobuf message via Jackson's {@link ObjectMapper} (with
 * {@link ProtobufJacksonModule} registered) and compares the output against the
 * canonical reference from {@link JsonFormat#printer()
 * JsonFormat.printer().omittingInsignificantWhitespace()}.
 *
 * <p>
 * Test groups:
 * <ul>
 * <li><b>ScalarTests</b> — all 15 scalar types, default value omission,
 * NaN/Infinity
 * <li><b>ComplexMessageTests</b> — nested messages, repeated fields, maps
 * (string/int/bool keys), oneof variants, enums
 * <li><b>WellKnownTypeTests</b> — Timestamp, Duration, FieldMask,
 * Struct/Value/ListValue, all 9 wrappers, Empty, Any (with TypeRegistry)
 * <li><b>OptionalAndCustomTests</b> — explicit presence (optional keyword),
 * custom json_name, recursive messages
 * <li><b>RoundtripTests</b> — encode→decode→equals for scalars, complex
 * messages, WKTs, and cross-library compatibility (Jackson module vs BuffJson)
 * </ul>
 */
class JacksonProto3ConformanceTest {

	/** ObjectMapper configured with the protobuf module (no TypeRegistry). */
	private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new ProtobufJacksonModule());

	/** Reference printer — the canonical proto3 JSON output we compare against. */
	private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();

	/** Encodes a message via Jackson and returns the JSON string. */
	private static String jacksonEncode(Message message) throws Exception {
		return MAPPER.writeValueAsString(message);
	}

	/**
	 * Asserts that Jackson's output for the given message matches
	 * {@code JsonFormat.printer()} exactly (byte-for-byte string comparison).
	 */
	private static void assertConformance(Message message) throws Exception {
		String expected = PRINTER.print(message);
		String actual = jacksonEncode(message);
		assertEquals(expected, actual,
				"Jackson output must match JsonFormat for " + message.getDescriptorForType().getName());
	}

	/** Verifies all 15 scalar types, default omission, and special float values. */
	@Nested
	class ScalarTests {

		@Test
		void allScalarTypes() throws Exception {
			var msg = JacksonTestScalars.newBuilder().setInt32Val(42).setInt64Val(Long.MAX_VALUE)
					.setUint32Val((int) 4294967295L).setUint64Val(-1L) // max uint64
					.setSint32Val(-100).setSint64Val(-200L).setFixed32Val(999).setFixed64Val(888L).setSfixed32Val(-777)
					.setSfixed64Val(-666L).setFloatVal(3.14f).setDoubleVal(2.718281828).setBoolVal(true)
					.setStringVal("hello world").setBytesVal(ByteString.copyFromUtf8("binary data")).build();
			assertConformance(msg);
		}

		@Test
		void defaultValues() throws Exception {
			// Default instance — all fields should be omitted
			assertConformance(JacksonTestScalars.getDefaultInstance());
		}

		@Test
		void specialFloats() throws Exception {
			var msg = JacksonTestScalars.newBuilder().setFloatVal(Float.NaN).setDoubleVal(Double.POSITIVE_INFINITY)
					.build();
			assertConformance(msg);
		}

		@Test
		void negativeInfinity() throws Exception {
			var msg = JacksonTestScalars.newBuilder().setDoubleVal(Double.NEGATIVE_INFINITY).build();
			assertConformance(msg);
		}
	}

	/** Verifies nested messages, repeated fields, maps, oneof, and enums. */
	@Nested
	class ComplexMessageTests {

		@Test
		void nestedMessage() throws Exception {
			var nested = JacksonNestedMessage.newBuilder().setId(1).setName("nested").build();
			var msg = JacksonTestComplex.newBuilder().setName("parent").setNested(nested).build();
			assertConformance(msg);
		}

		@Test
		void repeatedFields() throws Exception {
			var msg = JacksonTestComplex.newBuilder()
					.addRepeatedNested(JacksonNestedMessage.newBuilder().setId(1).setName("a"))
					.addRepeatedNested(JacksonNestedMessage.newBuilder().setId(2).setName("b"))
					.addRepeatedEnum(JacksonTestEnum.JACKSON_TEST_ENUM_FOO)
					.addRepeatedEnum(JacksonTestEnum.JACKSON_TEST_ENUM_BAR).build();
			assertConformance(msg);
		}

		@Test
		void mapFields() throws Exception {
			var msg = JacksonTestComplex.newBuilder().putStringMap("key1", "val1").putStringMap("key2", "val2")
					.putMessageMap("a", JacksonNestedMessage.newBuilder().setId(10).setName("ten").build())
					.putIntMap(42, "forty-two").putBoolMap(true, "yes").build();
			assertConformance(msg);
		}

		@Test
		void oneofInt() throws Exception {
			var msg = JacksonTestComplex.newBuilder().setName("oneof-test").setIntValue(99).build();
			assertConformance(msg);
		}

		@Test
		void oneofString() throws Exception {
			var msg = JacksonTestComplex.newBuilder().setStringValue("chosen").build();
			assertConformance(msg);
		}

		@Test
		void oneofMessage() throws Exception {
			var msg = JacksonTestComplex.newBuilder()
					.setMessageValue(JacksonNestedMessage.newBuilder().setId(5).setName("five")).build();
			assertConformance(msg);
		}

		@Test
		void enumField() throws Exception {
			var msg = JacksonTestComplex.newBuilder().setEnumVal(JacksonTestEnum.JACKSON_TEST_ENUM_BAR).build();
			assertConformance(msg);
		}
	}

	/** Verifies all 16 well-known types serialize to their canonical JSON forms. */
	@Nested
	class WellKnownTypeTests {

		@Test
		void timestamp() throws Exception {
			var msg = JacksonTestWellKnown.newBuilder()
					.setTimestamp(Timestamp.newBuilder().setSeconds(1704067200).setNanos(500000000)).build();
			assertConformance(msg);
		}

		@Test
		void duration() throws Exception {
			var msg = JacksonTestWellKnown.newBuilder()
					.setDuration(Duration.newBuilder().setSeconds(3600).setNanos(500000000)).build();
			assertConformance(msg);
		}

		@Test
		void fieldMask() throws Exception {
			var msg = JacksonTestWellKnown.newBuilder()
					.setFieldMask(FieldMask.newBuilder().addPaths("foo_bar").addPaths("baz_qux")).build();
			assertConformance(msg);
		}

		@Test
		void structAndValue() throws Exception {
			var struct = Struct.newBuilder().putFields("key", Value.newBuilder().setStringValue("val").build())
					.putFields("num", Value.newBuilder().setNumberValue(42.0).build())
					.putFields("flag", Value.newBuilder().setBoolValue(true).build())
					.putFields("nil", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()).build();
			var msg = JacksonTestWellKnown.newBuilder().setStructVal(struct)
					.setValue(Value.newBuilder().setStringValue("standalone")).build();
			assertConformance(msg);
		}

		@Test
		void listValue() throws Exception {
			var list = ListValue.newBuilder().addValues(Value.newBuilder().setNumberValue(1))
					.addValues(Value.newBuilder().setStringValue("two"))
					.addValues(Value.newBuilder().setBoolValue(false)).build();
			var msg = JacksonTestWellKnown.newBuilder().setListValue(list).build();
			assertConformance(msg);
		}

		@Test
		void wrappers() throws Exception {
			var msg = JacksonTestWellKnown.newBuilder().setInt32Wrapper(Int32Value.of(42))
					.setUint32Wrapper(UInt32Value.of(100)).setInt64Wrapper(Int64Value.of(Long.MAX_VALUE))
					.setUint64Wrapper(UInt64Value.of(-1L)).setFloatWrapper(FloatValue.of(1.5f))
					.setDoubleWrapper(DoubleValue.of(2.5)).setBoolWrapper(BoolValue.of(true))
					.setStringWrapper(StringValue.of("wrapped"))
					.setBytesWrapper(BytesValue.of(ByteString.copyFromUtf8("bytes"))).build();
			assertConformance(msg);
		}

		@Test
		void emptyType() throws Exception {
			var msg = JacksonTestWellKnown.newBuilder().setEmptyVal(Empty.getDefaultInstance()).build();
			assertConformance(msg);
		}

		@Test
		void anyType() throws Exception {
			var inner = JacksonNestedMessage.newBuilder().setId(42).setName("packed").build();
			var any = Any.pack(inner);
			var msg = JacksonTestWellKnown.newBuilder().setAnyVal(any).build();

			var registry = TypeRegistry.newBuilder().add(JacksonNestedMessage.getDescriptor()).build();
			var mapperWithRegistry = new ObjectMapper().registerModule(new ProtobufJacksonModule(registry));
			var printerWithRegistry = JsonFormat.printer().omittingInsignificantWhitespace()
					.usingTypeRegistry(registry);

			String expected = printerWithRegistry.print(msg);
			String actual = mapperWithRegistry.writeValueAsString(msg);
			assertEquals(expected, actual);
		}
	}

	/** Verifies optional fields, custom json_name, and recursive messages. */
	@Nested
	class OptionalAndCustomTests {

		@Test
		void optionalPresent() throws Exception {
			var msg = JacksonTestOptional.newBuilder().setOptionalInt32(0).setOptionalString("").setOptionalBool(false)
					.build();
			assertConformance(msg);
		}

		@Test
		void optionalAbsent() throws Exception {
			assertConformance(JacksonTestOptional.getDefaultInstance());
		}

		@Test
		void customJsonName() throws Exception {
			var msg = JacksonTestCustomJsonName.newBuilder().setValue(42).setName("custom").build();
			assertConformance(msg);
		}

		@Test
		void recursiveMessage() throws Exception {
			var msg = JacksonTestRecursive.newBuilder().setValue(1).setChild(JacksonTestRecursive.newBuilder()
					.setValue(2).setChild(JacksonTestRecursive.newBuilder().setValue(3))).build();
			assertConformance(msg);
		}
	}

	/**
	 * Encode→decode→equals roundtrip tests, plus cross-library compatibility
	 * (Jackson output is decodable by BuffJson and vice versa).
	 */
	@Nested
	class RoundtripTests {

		@Test
		void scalarRoundtrip() throws Exception {
			var original = JacksonTestScalars.newBuilder().setInt32Val(42).setInt64Val(123456789L).setUint32Val(100)
					.setStringVal("round trip").setBoolVal(true).setBytesVal(ByteString.copyFromUtf8("data"))
					.setFloatVal(1.5f).setDoubleVal(2.5).build();

			String json = MAPPER.writeValueAsString(original);
			JacksonTestScalars decoded = MAPPER.readValue(json, JacksonTestScalars.class);
			assertEquals(original, decoded);
		}

		@Test
		void complexRoundtrip() throws Exception {
			var original = JacksonTestComplex.newBuilder().setName("test")
					.setNested(JacksonNestedMessage.newBuilder().setId(1).setName("n"))
					.addRepeatedNested(JacksonNestedMessage.newBuilder().setId(2).setName("r"))
					.setEnumVal(JacksonTestEnum.JACKSON_TEST_ENUM_FOO).putStringMap("k", "v").setIntValue(99).build();

			String json = MAPPER.writeValueAsString(original);
			JacksonTestComplex decoded = MAPPER.readValue(json, JacksonTestComplex.class);
			assertEquals(original, decoded);
		}

		@Test
		void wellKnownRoundtrip() throws Exception {
			var original = JacksonTestWellKnown.newBuilder().setTimestamp(Timestamp.newBuilder().setSeconds(1704067200))
					.setDuration(Duration.newBuilder().setSeconds(60))
					.setFieldMask(FieldMask.newBuilder().addPaths("foo_bar")).setInt32Wrapper(Int32Value.of(42))
					.setStringWrapper(StringValue.of("w")).setBoolWrapper(BoolValue.of(true))
					.setEmptyVal(Empty.getDefaultInstance()).build();

			String json = MAPPER.writeValueAsString(original);
			JacksonTestWellKnown decoded = MAPPER.readValue(json, JacksonTestWellKnown.class);
			assertEquals(original, decoded);
		}

		@Test
		void crossLibraryCompatibility() throws Exception {
			// Encode with Jackson module, decode with BuffJson (and vice versa)
			var original = JacksonTestScalars.newBuilder().setInt32Val(42).setStringVal("cross").build();

			String jacksonJson = MAPPER.writeValueAsString(original);
			String buffJson = io.suboptimal.buffjson.BuffJson.encode(original);
			assertEquals(buffJson, jacksonJson, "Jackson and BuffJson output must match");

			JacksonTestScalars fromJackson = MAPPER.readValue(jacksonJson, JacksonTestScalars.class);
			JacksonTestScalars fromBuff = io.suboptimal.buffjson.BuffJson.decode(buffJson, JacksonTestScalars.class);
			assertEquals(original, fromJackson);
			assertEquals(original, fromBuff);
		}
	}
}
