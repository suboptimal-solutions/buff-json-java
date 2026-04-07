package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.proto.*;
import io.suboptimal.buffjson.schema.ProtobufSchema;
import io.suboptimal.buffjson.swagger.ProtobufModelConverter;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.media.Schema;

@SuppressWarnings("rawtypes")
class BuffJsonSwaggerTest {

	private ModelConverters converters;

	@BeforeEach
	void setUp() {
		converters = new ModelConverters(true);
		converters.addConverter(new ProtobufModelConverter());
	}

	@Test
	void scalarTypes() {
		ResolvedSchema resolved = resolve(TestAllScalars.class);
		Schema schema = resolved.schema;

		assertType("object", schema);
		assertEquals("TestAllScalars", schema.getTitle());

		Map<String, Schema> props = properties(schema);
		assertNotNull(props);

		// int32 → integer
		assertType("integer", props.get("optionalInt32"));

		// uint32 → integer with minimum 0
		assertType("integer", props.get("optionalUint32"));
		assertEquals(0, props.get("optionalUint32").getMinimum().intValue());

		// int64 → string with format int64
		assertType("string", props.get("optionalInt64"));
		assertEquals("int64", props.get("optionalInt64").getFormat());

		// uint64 → string with format uint64
		assertType("string", props.get("optionalUint64"));
		assertEquals("uint64", props.get("optionalUint64").getFormat());

		// float/double → oneOf [number, string]
		assertFloatSchema(props.get("optionalFloat"));
		assertFloatSchema(props.get("optionalDouble"));

		// bool → boolean
		assertType("boolean", props.get("optionalBool"));

		// string → string
		assertType("string", props.get("optionalString"));

		// bytes → string with contentEncoding base64
		assertType("string", props.get("optionalBytes"));
		assertEquals("base64", props.get("optionalBytes").getContentEncoding());
	}

	@Test
	void nestedMessages() {
		ResolvedSchema resolved = resolve(TestNesting.class);
		Map<String, Schema> props = properties(resolved.schema);

		Schema nested = props.get("nested");
		assertType("object", nested);
		assertEquals("NestedMessage", nested.getTitle());

		Map<String, Schema> nestedProps = properties(nested);
		assertType("integer", nestedProps.get("value"));
		assertType("string", nestedProps.get("name"));

		// repeated nested → array
		Schema repeatedNested = props.get("repeatedNested");
		assertType("array", repeatedNested);
		assertNotNull(repeatedNested.getItems());
	}

	@Test
	void recursiveMessages() {
		ResolvedSchema resolved = resolve(TestRecursive.class);

		// Root should be a $ref to the full proto name
		assertEquals("#/components/schemas/io.suboptimal.buffjson.proto.TestRecursive", resolved.schema.get$ref());

		// Definition should be registered with full proto name
		assertNotNull(resolved.referencedSchemas);
		String fullName = "io.suboptimal.buffjson.proto.TestRecursive";
		assertTrue(resolved.referencedSchemas.containsKey(fullName));

		Schema def = resolved.referencedSchemas.get(fullName);
		assertType("object", def);
		assertEquals("TestRecursive", def.getTitle());
	}

	@Test
	void enumFields() {
		ResolvedSchema resolved = resolve(TestNesting.class);
		Schema enumSchema = properties(resolved.schema).get("enumValue");

		assertType("string", enumSchema);
		assertEquals("TestEnum", enumSchema.getTitle());

		List enumValues = enumSchema.getEnum();
		assertNotNull(enumValues);
		assertTrue(enumValues.contains("TEST_ENUM_FOO"));
		assertTrue(enumValues.contains("TEST_ENUM_BAR"));
	}

	@Test
	void mapFields() {
		ResolvedSchema resolved = resolve(TestMaps.class);
		Map<String, Schema> props = properties(resolved.schema);

		// map<string, string> → object with additionalProperties: string
		Schema stringToString = props.get("stringToString");
		assertType("object", stringToString);
		Schema addlProps = (Schema) stringToString.getAdditionalProperties();
		assertType("string", addlProps);

		// map<string, int32> → object with additionalProperties: integer
		Schema stringToInt32 = props.get("stringToInt32");
		assertType("object", stringToInt32);
		Schema intProps = (Schema) stringToInt32.getAdditionalProperties();
		assertType("integer", intProps);
	}

	@Test
	void repeatedFields() {
		ResolvedSchema resolved = resolve(TestRepeatedScalars.class);
		Map<String, Schema> props = properties(resolved.schema);

		// repeated int32 → array of integer
		Schema repeatedInt = props.get("repeatedInt32");
		assertType("array", repeatedInt);
		assertType("integer", repeatedInt.getItems());

		// repeated string → array of string
		Schema repeatedStr = props.get("repeatedString");
		assertType("array", repeatedStr);
		assertType("string", repeatedStr.getItems());
	}

	@Test
	void timestampType() {
		ResolvedSchema resolved = resolve(TestTimestamp.class);
		Schema ts = properties(resolved.schema).get("value");

		assertType("string", ts);
		assertEquals("date-time", ts.getFormat());
		assertNotNull(ts.getDescription());
	}

	@Test
	void durationType() {
		ResolvedSchema resolved = resolve(TestDuration.class);
		Schema dur = properties(resolved.schema).get("value");

		assertType("string", dur);
		assertNotNull(dur.getDescription());
	}

	@Test
	void structType() {
		ResolvedSchema resolved = resolve(TestStruct.class);
		Schema struct = properties(resolved.schema).get("structValue");

		assertType("object", struct);
		assertNotNull(struct.getDescription());
	}

	@Test
	void anyType() {
		ResolvedSchema resolved = resolve(TestAny.class);
		Schema any = properties(resolved.schema).get("value");

		assertType("object", any);
		assertNotNull(any.getRequired());
		assertTrue(any.getRequired().contains("@type"));
	}

	@Test
	void nonProtobufTypeDelegatesToChain() {
		ResolvedSchema resolved = converters.resolveAsResolvedSchema(new AnnotatedType(String.class));
		// Should be handled by the default converter, not fail
		assertNotNull(resolved);
		assertNotNull(resolved.schema);
	}

	@Test
	void isOpenapi31() {
		ProtobufModelConverter converter = new ProtobufModelConverter();
		assertTrue(converter.isOpenapi31());
	}

	// --- schema validation: BuffJson output validates against generated schema ---

	@Test
	void buffJsonOutputValidatesAgainstSchema_allScalars() {
		assertJsonValidatesAgainstSchema(TestAllScalars.newBuilder().setOptionalInt32(42)
				.setOptionalInt64(123456789012345L).setOptionalUint32(100).setOptionalUint64(999999999999L)
				.setOptionalSint32(-10).setOptionalSint64(-99999L).setOptionalFixed32(7).setOptionalFixed64(8L)
				.setOptionalSfixed32(9).setOptionalSfixed64(10L).setOptionalFloat(3.14f).setOptionalDouble(2.718281828)
				.setOptionalBool(true).setOptionalString("hello world")
				.setOptionalBytes(ByteString.copyFromUtf8("binary")).build());
	}

	@Test
	void buffJsonOutputValidatesAgainstSchema_nested() {
		assertJsonValidatesAgainstSchema(
				TestNesting.newBuilder().setNested(NestedMessage.newBuilder().setValue(42).setName("test"))
						.addRepeatedNested(NestedMessage.newBuilder().setValue(1).setName("a"))
						.addRepeatedNested(NestedMessage.newBuilder().setValue(2).setName("b"))
						.setEnumValue(TestEnum.TEST_ENUM_FOO).addRepeatedEnum(TestEnum.TEST_ENUM_BAR)
						.addRepeatedEnum(TestEnum.TEST_ENUM_BAZ).build());
	}

	@Test
	void buffJsonOutputValidatesAgainstSchema_maps() {
		assertJsonValidatesAgainstSchema(TestMaps.newBuilder().putStringToString("key1", "value1")
				.putStringToString("key2", "value2").putStringToInt32("count", 42)
				.putStringToMessage("msg", NestedMessage.newBuilder().setValue(1).setName("x").build()).build());
	}

	@Test
	void buffJsonOutputValidatesAgainstSchema_recursive() {
		assertJsonValidatesAgainstSchema(TestRecursive.newBuilder().setValue(1)
				.setChild(TestRecursive.newBuilder().setValue(2).setChild(TestRecursive.newBuilder().setValue(3)))
				.build());
	}

	@Test
	void buffJsonOutputValidatesAgainstSchema_timestamp() {
		assertJsonValidatesAgainstSchema(TestTimestamp.newBuilder()
				.setValue(Timestamp.newBuilder().setSeconds(1711627200).setNanos(123456789)).build());
	}

	@Test
	void buffJsonOutputValidatesAgainstSchema_duration() {
		assertJsonValidatesAgainstSchema(
				TestDuration.newBuilder().setValue(Duration.newBuilder().setSeconds(3600).setNanos(500000000)).build());
	}

	@Test
	void buffJsonOutputValidatesAgainstSchema_oneof() {
		assertJsonValidatesAgainstSchema(TestOneof.newBuilder().setName("test").setStringValue("hello").build());
	}

	@Test
	void buffJsonOutputValidatesAgainstSchema_defaultInstance() {
		assertJsonValidatesAgainstSchema(TestAllScalars.getDefaultInstance());
		assertJsonValidatesAgainstSchema(TestNesting.getDefaultInstance());
		assertJsonValidatesAgainstSchema(TestMaps.getDefaultInstance());
	}

	// --- helpers ---

	private ResolvedSchema resolve(Class<?> type) {
		ResolvedSchema resolved = converters.resolveAsResolvedSchema(new AnnotatedType(type));
		assertNotNull(resolved, "ResolvedSchema should not be null for " + type.getSimpleName());
		assertNotNull(resolved.schema, "Schema should not be null for " + type.getSimpleName());
		return resolved;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Schema> properties(Schema schema) {
		return schema.getProperties();
	}

	@SuppressWarnings("unchecked")
	private void assertFloatSchema(Schema schema) {
		List<Schema> oneOf = schema.getOneOf();
		assertNotNull(oneOf, "float/double should use oneOf");
		assertEquals(2, oneOf.size());
		assertType("number", oneOf.get(0));
		assertType("string", oneOf.get(1));
		assertNotNull(oneOf.get(1).getEnum());
		assertTrue(oneOf.get(1).getEnum().contains("NaN"));
	}

	private static final BuffJsonEncoder ENCODER = BuffJson.encoder();
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory
			.getInstance(SpecVersion.VersionFlag.V202012);
	private static final SchemaValidatorsConfig VALIDATORS_CONFIG = SchemaValidatorsConfig.builder().build();

	private static void assertType(String expected, Schema schema) {
		assertNotNull(schema.getTypes(), "types must not be null");
		assertTrue(schema.getTypes().contains(expected),
				"expected type '" + expected + "' but got " + schema.getTypes());
	}

	private void assertJsonValidatesAgainstSchema(Message message) {
		String json = ENCODER.encode(message);
		Map<String, Object> schemaMap = ProtobufSchema.generate(message.getDescriptorForType());

		JsonNode schemaNode = MAPPER.valueToTree(schemaMap);
		JsonNode jsonNode;
		try {
			jsonNode = MAPPER.readTree(json);
		} catch (Exception e) {
			fail("Invalid JSON from BuffJson: " + e.getMessage());
			return;
		}

		JsonSchema jsonSchema = SCHEMA_FACTORY.getSchema(schemaNode, VALIDATORS_CONFIG);
		Set<ValidationMessage> errors = jsonSchema.validate(jsonNode);
		assertTrue(errors.isEmpty(),
				"BuffJson output should validate against generated schema.\nJSON: " + json + "\nErrors: " + errors);
	}
}
