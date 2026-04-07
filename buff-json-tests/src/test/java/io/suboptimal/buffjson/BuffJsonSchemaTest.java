package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.proto.*;
import io.suboptimal.buffjson.schema.ProtobufSchema;

class BuffJsonSchemaTest {

	@Test
	void allScalarTypes() {
		Map<String, Object> schema = ProtobufSchema.generate(TestAllScalars.getDescriptor());

		assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema"));
		assertEquals("object", schema.get("type"));
		assertEquals("TestAllScalars", schema.get("title"));

		@SuppressWarnings("unchecked")
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");
		assertNotNull(props);

		// int32, sint32, sfixed32 → integer
		assertEquals(Map.of("type", "integer"), props.get("optionalInt32"));
		assertEquals(Map.of("type", "integer"), props.get("optionalSint32"));
		assertEquals(Map.of("type", "integer"), props.get("optionalSfixed32"));

		// uint32, fixed32 → integer with minimum 0
		assertIntegerWithMinZero(props.get("optionalUint32"));
		assertIntegerWithMinZero(props.get("optionalFixed32"));

		// int64, sint64, sfixed64 → string with format int64
		assertStringWithFormat(props.get("optionalInt64"), "int64");
		assertStringWithFormat(props.get("optionalSint64"), "int64");
		assertStringWithFormat(props.get("optionalSfixed64"), "int64");

		// uint64, fixed64 → string with format uint64
		assertStringWithFormat(props.get("optionalUint64"), "uint64");
		assertStringWithFormat(props.get("optionalFixed64"), "uint64");

		// float, double → oneOf [number, string enum]
		assertFloatSchema(props.get("optionalFloat"));
		assertFloatSchema(props.get("optionalDouble"));

		// bool → boolean
		assertEquals(Map.of("type", "boolean"), props.get("optionalBool"));

		// string → string
		assertEquals(Map.of("type", "string"), props.get("optionalString"));

		// bytes → string with contentEncoding base64
		assertBytesSchema(props.get("optionalBytes"));
	}

	@Test
	void repeatedScalars() {
		Map<String, Object> schema = ProtobufSchema.generate(TestRepeatedScalars.getDescriptor());

		@SuppressWarnings("unchecked")
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");

		// repeated int32 → array of integer
		assertArrayOf(props.get("repeatedInt32"), Map.of("type", "integer"));

		// repeated string → array of string
		assertArrayOf(props.get("repeatedString"), Map.of("type", "string"));

		// repeated bool → array of boolean
		assertArrayOf(props.get("repeatedBool"), Map.of("type", "boolean"));
	}

	@Test
	void nestedMessages() {
		Map<String, Object> schema = ProtobufSchema.generate(TestNesting.getDescriptor());

		@SuppressWarnings("unchecked")
		Map<String, Object> defs = (Map<String, Object>) schema.get("$defs");
		assertNotNull(defs, "Named types should be in $defs");

		@SuppressWarnings("unchecked")
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");

		// nested message → $ref to $defs
		assertRef("io.suboptimal.buffjson.proto.NestedMessage", props.get("nested"));

		@SuppressWarnings("unchecked")
		Map<String, Object> nestedSchema = (Map<String, Object>) defs.get("io.suboptimal.buffjson.proto.NestedMessage");
		assertEquals("object", nestedSchema.get("type"));
		assertEquals("NestedMessage", nestedSchema.get("title"));
		@SuppressWarnings("unchecked")
		Map<String, Object> nestedProps = (Map<String, Object>) nestedSchema.get("properties");
		assertEquals(Map.of("type", "integer"), nestedProps.get("value"));
		assertEquals(Map.of("type", "string"), nestedProps.get("name"));

		// repeated nested → array of $ref
		@SuppressWarnings("unchecked")
		Map<String, Object> repeatedNested = (Map<String, Object>) props.get("repeatedNested");
		assertEquals("array", repeatedNested.get("type"));
		assertRef("io.suboptimal.buffjson.proto.NestedMessage", repeatedNested.get("items"));

		// enum → $ref to $defs
		assertRef("io.suboptimal.buffjson.proto.TestEnum", props.get("enumValue"));

		@SuppressWarnings("unchecked")
		Map<String, Object> enumSchema = (Map<String, Object>) defs.get("io.suboptimal.buffjson.proto.TestEnum");
		assertEquals("string", enumSchema.get("type"));
		assertEquals("TestEnum", enumSchema.get("title"));
		assertEquals(List.of("TEST_ENUM_UNSPECIFIED", "TEST_ENUM_FOO", "TEST_ENUM_BAR", "TEST_ENUM_BAZ"),
				enumSchema.get("enum"));
	}

	@Test
	void recursiveMessages() {
		Map<String, Object> schema = ProtobufSchema.generate(TestRecursive.getDescriptor());

		// Should have $defs for the recursive type
		assertNotNull(schema.get("$defs"), "Recursive message should produce $defs");

		@SuppressWarnings("unchecked")
		Map<String, Object> defs = (Map<String, Object>) schema.get("$defs");
		assertTrue(defs.containsKey("io.suboptimal.buffjson.proto.TestRecursive"));

		// Root should be a $ref
		assertEquals("#/$defs/io.suboptimal.buffjson.proto.TestRecursive", schema.get("$ref"));
	}

	@Test
	void oneofFields() {
		Map<String, Object> schema = ProtobufSchema.generate(TestOneof.getDescriptor());

		@SuppressWarnings("unchecked")
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");

		// All oneof variants should be in properties
		assertNotNull(props.get("intValue"));
		assertNotNull(props.get("stringValue"));
		assertNotNull(props.get("boolValue"));
		assertNotNull(props.get("messageValue"));
		assertNotNull(props.get("enumValue"));
		// Plus the non-oneof field
		assertNotNull(props.get("name"));
	}

	@Test
	void mapFields() {
		Map<String, Object> schema = ProtobufSchema.generate(TestMaps.getDescriptor());

		@SuppressWarnings("unchecked")
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");

		// map<string, string> → object with additionalProperties: string
		assertMapOf(props.get("stringToString"), Map.of("type", "string"));

		// map<string, int32> → object with additionalProperties: integer
		assertMapOf(props.get("stringToInt32"), Map.of("type", "integer"));

		// map<string, NestedMessage> → object with additionalProperties: $ref
		@SuppressWarnings("unchecked")
		Map<String, Object> msgMap = (Map<String, Object>) props.get("stringToMessage");
		assertEquals("object", msgMap.get("type"));
		assertRef("io.suboptimal.buffjson.proto.NestedMessage", msgMap.get("additionalProperties"));
	}

	@Test
	void wrapperTypes() {
		Map<String, Object> schema = ProtobufSchema.generate(TestWrappers.getDescriptor());

		@SuppressWarnings("unchecked")
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");

		assertEquals(Map.of("type", "integer"), props.get("int32Value"));
		assertIntegerWithMinZero(props.get("uint32Value"));
		assertStringWithFormat(props.get("int64Value"), "int64");
		assertStringWithFormat(props.get("uint64Value"), "uint64");
		assertFloatSchema(props.get("floatValue"));
		assertFloatSchema(props.get("doubleValue"));
		assertEquals(Map.of("type", "boolean"), props.get("boolValue"));
		assertEquals(Map.of("type", "string"), props.get("stringValue"));
		assertBytesSchema(props.get("bytesValue"));
	}

	@Test
	void timestampType() {
		Map<String, Object> schema = ProtobufSchema.generate(TestTimestamp.getDescriptor());

		@SuppressWarnings("unchecked")
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");
		@SuppressWarnings("unchecked")
		Map<String, Object> valueSchema = (Map<String, Object>) props.get("value");
		assertEquals("string", valueSchema.get("type"));
		assertEquals("date-time", valueSchema.get("format"));
		assertEquals("RFC 3339 date-time format.", valueSchema.get("description"));
	}

	@Test
	void durationType() {
		Map<String, Object> schema = ProtobufSchema.generate(TestDuration.getDescriptor());

		@SuppressWarnings("unchecked")
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");
		@SuppressWarnings("unchecked")
		Map<String, Object> valueSchema = (Map<String, Object>) props.get("value");
		assertEquals("string", valueSchema.get("type"));
		assertEquals("Signed seconds with up to 9 fractional digits, suffixed with 's'.",
				valueSchema.get("description"));
	}

	@Test
	void fieldMaskType() {
		Map<String, Object> schema = ProtobufSchema.generate(TestFieldMask.getDescriptor());

		@SuppressWarnings("unchecked")
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");
		@SuppressWarnings("unchecked")
		Map<String, Object> valueSchema = (Map<String, Object>) props.get("value");
		assertEquals("string", valueSchema.get("type"));
		assertEquals("Comma-separated camelCase field paths.", valueSchema.get("description"));
	}

	@Test
	void structValueListValue() {
		Map<String, Object> schema = ProtobufSchema.generate(TestStruct.getDescriptor());

		@SuppressWarnings("unchecked")
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");

		// Struct → object with description
		@SuppressWarnings("unchecked")
		Map<String, Object> structSchema = (Map<String, Object>) props.get("structValue");
		assertEquals("object", structSchema.get("type"));
		assertEquals("Arbitrary JSON object.", structSchema.get("description"));

		// Value → any with description
		@SuppressWarnings("unchecked")
		Map<String, Object> valueSchema = (Map<String, Object>) props.get("value");
		assertFalse(valueSchema.containsKey("type"), "Value should not have a type constraint");
		assertEquals("Arbitrary JSON value.", valueSchema.get("description"));

		// ListValue → array with description
		@SuppressWarnings("unchecked")
		Map<String, Object> listSchema = (Map<String, Object>) props.get("listValue");
		assertEquals("array", listSchema.get("type"));
		assertEquals("JSON array of arbitrary values.", listSchema.get("description"));
	}

	@Test
	void anyType() {
		Map<String, Object> schema = ProtobufSchema.generate(TestAny.getDescriptor());

		@SuppressWarnings("unchecked")
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");
		@SuppressWarnings("unchecked")
		Map<String, Object> anySchema = (Map<String, Object>) props.get("value");
		assertEquals("object", anySchema.get("type"));
		assertEquals(List.of("@type"), anySchema.get("required"));
		assertEquals("Arbitrary message identified by a type URL in @type.", anySchema.get("description"));
	}

	@Test
	void emptyType() {
		Map<String, Object> schema = ProtobufSchema.generate(TestEmpty.getDescriptor());

		@SuppressWarnings("unchecked")
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");
		assertEquals(Map.of("type", "object"), props.get("value"));
	}

	@Test
	void customJsonName() {
		Map<String, Object> schema = ProtobufSchema.generate(TestCustomJsonName.getDescriptor());

		@SuppressWarnings("unchecked")
		Map<String, Object> props = (Map<String, Object>) schema.get("properties");
		// Custom JSON names should be used as property keys
		assertNotNull(props.get("@value"));
		assertNotNull(props.get("Name"));
	}

	@Test
	void generateFromClass() {
		Map<String, Object> schema = ProtobufSchema.generate(TestAllScalars.class);
		assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$schema"));
		assertEquals("object", schema.get("type"));
		assertEquals("TestAllScalars", schema.get("title"));
		assertNotNull(schema.get("properties"));
	}

	@Test
	void protoCommentsFromGeneratedRegistry() {
		// Comments from conformance_test.proto are available via the generated
		// ProtoCommentProvider (protoc plugin extracts them from SourceCodeInfo)
		Map<String, Object> schema = ProtobufSchema.generate(TestAllScalars.getDescriptor());
		assertEquals("Covers all scalar types", schema.get("description"));

		// Recursive message — description lives inside $defs
		Map<String, Object> recursiveSchema = ProtobufSchema.generate(TestRecursive.getDescriptor());
		@SuppressWarnings("unchecked")
		Map<String, Object> defs = (Map<String, Object>) recursiveSchema.get("$defs");
		@SuppressWarnings("unchecked")
		Map<String, Object> recursiveDef = (Map<String, Object>) defs.get("io.suboptimal.buffjson.proto.TestRecursive");
		assertEquals("Recursive message", recursiveDef.get("description"));

		// Map fields
		Map<String, Object> mapsSchema = ProtobufSchema.generate(TestMaps.getDescriptor());
		assertEquals("Map fields with various key types", mapsSchema.get("description"));

		// Enum comment — enum is in $defs
		Map<String, Object> nestingSchema = ProtobufSchema.generate(TestNesting.getDescriptor());
		@SuppressWarnings("unchecked")
		Map<String, Object> nestingDefs = (Map<String, Object>) nestingSchema.get("$defs");
		@SuppressWarnings("unchecked")
		Map<String, Object> enumSchema = (Map<String, Object>) nestingDefs.get("io.suboptimal.buffjson.proto.TestEnum");
		assertEquals("Nested messages and enums", enumSchema.get("description"));

		// NestedMessage has no comment — no description
		Map<String, Object> nestedSchema = ProtobufSchema.generate(NestedMessage.getDescriptor());
		assertNull(nestedSchema.get("description"));
	}

	// --- assertion helpers ---

	@SuppressWarnings("unchecked")
	private void assertRef(String expectedFullName, Object schema) {
		Map<String, Object> s = (Map<String, Object>) schema;
		assertEquals("#/$defs/" + expectedFullName, s.get("$ref"));
	}

	@SuppressWarnings("unchecked")
	private void assertFloatSchema(Object schema) {
		Map<String, Object> s = (Map<String, Object>) schema;
		List<Object> oneOf = (List<Object>) s.get("oneOf");
		assertNotNull(oneOf, "float/double should use oneOf");
		assertEquals(2, oneOf.size());
		assertEquals(Map.of("type", "number"), oneOf.get(0));
		Map<String, Object> strEnum = (Map<String, Object>) oneOf.get(1);
		assertEquals("string", strEnum.get("type"));
		assertEquals(List.of("NaN", "Infinity", "-Infinity"), strEnum.get("enum"));
	}

	@SuppressWarnings("unchecked")
	private void assertArrayOf(Object schema, Map<String, Object> expectedItems) {
		Map<String, Object> s = (Map<String, Object>) schema;
		assertEquals("array", s.get("type"));
		assertEquals(expectedItems, s.get("items"));
	}

	@SuppressWarnings("unchecked")
	private void assertMapOf(Object schema, Map<String, Object> expectedValueSchema) {
		Map<String, Object> s = (Map<String, Object>) schema;
		assertEquals("object", s.get("type"));
		assertEquals(expectedValueSchema, s.get("additionalProperties"));
	}

	@SuppressWarnings("unchecked")
	private void assertIntegerWithMinZero(Object schema) {
		Map<String, Object> s = (Map<String, Object>) schema;
		assertEquals("integer", s.get("type"));
		assertEquals(0, s.get("minimum"));
	}

	@SuppressWarnings("unchecked")
	private void assertStringWithFormat(Object schema, String expectedFormat) {
		Map<String, Object> s = (Map<String, Object>) schema;
		assertEquals("string", s.get("type"));
		assertEquals(expectedFormat, s.get("format"));
	}

	@SuppressWarnings("unchecked")
	private void assertBytesSchema(Object schema) {
		Map<String, Object> s = (Map<String, Object>) schema;
		assertEquals("string", s.get("type"));
		assertEquals("base64", s.get("contentEncoding"));
	}
}
