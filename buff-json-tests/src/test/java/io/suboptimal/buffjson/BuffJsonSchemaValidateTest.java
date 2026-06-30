package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.proto.*;
import io.suboptimal.buffjson.schema.ProtobufSchema;

/**
 * Tests for buf.validate constraint support in JSON Schema generation.
 *
 * <p>
 * Verifies that {@link ProtobufSchema} correctly maps buf.validate annotations
 * from proto fields to JSON Schema keywords. Covers three areas:
 * <ol>
 * <li><b>Constraint-to-keyword mapping</b> — each constraint type (string,
 * numeric, repeated, map, enum, required) produces the correct JSON Schema
 * keyword ({@code minLength}, {@code maximum}, {@code minItems}, etc.)
 * <li><b>Float/double handling</b> — constrained floats/doubles place
 * constraints on the {@code number} branch of the {@code oneOf}; the
 * {@code finite} flag collapses the schema to plain {@code number}
 * <li><b>Comment + constraint coexistence</b> — proto source comments and
 * validate constraints merge correctly in the {@code description} field
 * </ol>
 *
 * <p>
 * Test messages are defined in {@code validate_test.proto} with field-level
 * comments and buf.validate annotations.
 *
 * @see ValidateConstraints
 */
class BuffJsonSchemaValidateTest {

	@Test
	void stringMinMaxLen() {
		var schema = ProtobufSchema.generate(TestValidateString.getDescriptor());
		var props = props(schema);
		var name = field(props, "name");
		assertEquals(1L, name.get("minLength"));
		assertEquals(100L, name.get("maxLength"));
	}

	@Test
	void stringEmailFormat() {
		var props = props(ProtobufSchema.generate(TestValidateString.getDescriptor()));
		assertEquals("email", field(props, "email").get("format"));
	}

	@Test
	void stringPattern() {
		var props = props(ProtobufSchema.generate(TestValidateString.getDescriptor()));
		assertEquals("^[a-z]+$", field(props, "patternField").get("pattern"));
	}

	@Test
	void stringUuidFormat() {
		var props = props(ProtobufSchema.generate(TestValidateString.getDescriptor()));
		assertEquals("uuid", field(props, "uuidField").get("format"));
	}

	@Test
	void stringUriFormat() {
		var props = props(ProtobufSchema.generate(TestValidateString.getDescriptor()));
		assertEquals("uri", field(props, "uriField").get("format"));
	}

	@Test
	void stringHostnameFormat() {
		var props = props(ProtobufSchema.generate(TestValidateString.getDescriptor()));
		assertEquals("hostname", field(props, "hostnameField").get("format"));
	}

	@Test
	void stringConst() {
		var props = props(ProtobufSchema.generate(TestValidateString.getDescriptor()));
		assertEquals("fixed", field(props, "constField").get("const"));
	}

	@Test
	void stringIn() {
		var props = props(ProtobufSchema.generate(TestValidateString.getDescriptor()));
		assertEquals(List.of("a", "b", "c"), field(props, "inField").get("enum"));
	}

	@Test
	void stringIpv4Format() {
		var props = props(ProtobufSchema.generate(TestValidateString.getDescriptor()));
		assertEquals("ipv4", field(props, "ipv4Field").get("format"));
	}

	@Test
	void stringIpv6Format() {
		var props = props(ProtobufSchema.generate(TestValidateString.getDescriptor()));
		assertEquals("ipv6", field(props, "ipv6Field").get("format"));
	}

	@Test
	void intRangeInclusive() {
		var props = props(ProtobufSchema.generate(TestValidateNumbers.getDescriptor()));
		var age = field(props, "age");
		assertEquals(0, age.get("minimum"));
		assertEquals(150, age.get("maximum"));
	}

	@Test
	void intRangeExclusive() {
		var props = props(ProtobufSchema.generate(TestValidateNumbers.getDescriptor()));
		var score = field(props, "score");
		assertEquals(0, score.get("exclusiveMinimum"));
		assertEquals(1000, score.get("exclusiveMaximum"));
	}

	@Test
	void uint32Minimum() {
		var props = props(ProtobufSchema.generate(TestValidateNumbers.getDescriptor()));
		var count = field(props, "count");
		assertEquals(1, count.get("minimum"));
	}

	@Test
	void int64ConstraintsInDescription() {
		var props = props(ProtobufSchema.generate(TestValidateNumbers.getDescriptor()));
		var bigValue = field(props, "bigValue");
		// int64 maps to string in JSON, so numeric constraints go to description
		String desc = (String) bigValue.get("description");
		assertNotNull(desc, "int64 constraints should produce a description");
		assertTrue(desc.contains(">= 0"), "Should describe gte constraint");
		assertTrue(desc.contains("<= 1000000"), "Should describe lte constraint");
	}

	@Test
	void requiredFields() {
		var schema = ProtobufSchema.generate(TestValidateRequired.getDescriptor());
		@SuppressWarnings("unchecked")
		List<String> required = (List<String>) schema.get("required");
		assertNotNull(required, "Should have required array");
		assertTrue(required.contains("requiredName"));
		assertTrue(required.contains("requiredAge"));
		assertFalse(required.contains("optionalName"));
	}

	@Test
	void repeatedConstraints() {
		var props = props(ProtobufSchema.generate(TestValidateRepeated.getDescriptor()));
		var tags = field(props, "tags");
		assertEquals(1L, tags.get("minItems"));
		assertEquals(10L, tags.get("maxItems"));
		assertEquals(true, tags.get("uniqueItems"));
	}

	@Test
	void mapConstraints() {
		var props = props(ProtobufSchema.generate(TestValidateMap.getDescriptor()));
		var metadata = field(props, "metadata");
		assertEquals(1L, metadata.get("minProperties"));
		assertEquals(50L, metadata.get("maxProperties"));
	}

	@Test
	void enumInConstraint() {
		var props = props(ProtobufSchema.generate(TestValidateEnum.getDescriptor()));
		var priority = field(props, "priority");
		assertEquals(List.of("PRIORITY_LOW", "PRIORITY_MEDIUM", "PRIORITY_HIGH"), priority.get("enum"));
	}

	// --- float/double constraint handling ---

	@Test
	void doubleWithConstraints() {
		var props = props(ProtobufSchema.generate(TestValidateNumbers.getDescriptor()));
		var ratio = field(props, "ratio");
		// Constraints should be on the number branch of the oneOf
		@SuppressWarnings("unchecked")
		var oneOf = (List<Map<String, Object>>) ratio.get("oneOf");
		assertNotNull(oneOf, "Should still use oneOf");
		assertEquals(2, oneOf.size());
		// number branch has constraints
		var numberBranch = oneOf.get(0);
		assertEquals("number", numberBranch.get("type"));
		assertEquals(0.0, numberBranch.get("minimum"));
		assertEquals(1.0, numberBranch.get("maximum"));
		// string branch unchanged
		var stringBranch = oneOf.get(1);
		assertEquals("string", stringBranch.get("type"));
		// no-presence default survives the oneOf rebuild (not just description)
		assertEquals(0.0, ratio.get("default"));
	}

	@Test
	void floatFiniteCollapsesToNumber() {
		var props = props(ProtobufSchema.generate(TestValidateNumbers.getDescriptor()));
		var temp = field(props, "temperature");
		// finite: true collapses oneOf to plain number
		assertEquals("number", temp.get("type"));
		assertNull(temp.get("oneOf"), "Should not have oneOf when finite");
		assertEquals(-273.15f, temp.get("minimum"));
		// no-presence default survives the finite collapse to plain number
		assertEquals(0.0, temp.get("default"));
	}

	@Test
	void doubleWithoutConstraintsUsesOneOf() {
		var props = props(ProtobufSchema.generate(TestValidateNumbers.getDescriptor()));
		var weight = field(props, "weight");
		// No constraints, standard oneOf schema
		assertNotNull(weight.get("oneOf"), "Unconstrained double should use oneOf");
		assertNull(weight.get("type"), "Should not have top-level type");
		// no-presence default present on the unconstrained (early-return) path too
		assertEquals(0.0, weight.get("default"));
	}

	// --- comment + constraint coexistence ---

	@Test
	void commentWithStringConstraints() {
		var props = props(ProtobufSchema.generate(TestValidateString.getDescriptor()));
		// Field with comment and minLength/maxLength — description is just the comment
		var name = field(props, "name");
		assertEquals("Display name of the entity", name.get("description"));
		assertEquals(1L, name.get("minLength"));
		assertEquals(100L, name.get("maxLength"));
	}

	@Test
	void commentWithFormatConstraint() {
		var props = props(ProtobufSchema.generate(TestValidateString.getDescriptor()));
		// Field with comment and format — both present, description is just the comment
		var email = field(props, "email");
		assertEquals("Contact email address", email.get("description"));
		assertEquals("email", email.get("format"));
	}

	@Test
	void commentWithPatternConstraint() {
		var props = props(ProtobufSchema.generate(TestValidateString.getDescriptor()));
		var pattern = field(props, "patternField");
		assertEquals("Lowercase identifier", pattern.get("description"));
		assertEquals("^[a-z]+$", pattern.get("pattern"));
	}

	@Test
	void commentWithNumericConstraints() {
		var props = props(ProtobufSchema.generate(TestValidateNumbers.getDescriptor()));
		var age = field(props, "age");
		assertEquals("Person's age in years", age.get("description"));
		assertEquals(0, age.get("minimum"));
		assertEquals(150, age.get("maximum"));
	}

	@Test
	void commentWithInt64ConstraintsInDescription() {
		var props = props(ProtobufSchema.generate(TestValidateNumbers.getDescriptor()));
		var bigValue = field(props, "bigValue");
		// int64 constraints are appended to the comment
		String desc = (String) bigValue.get("description");
		assertNotNull(desc);
		assertTrue(desc.startsWith("Large numeric value"), "Should start with proto comment");
		assertTrue(desc.contains(">= 0"), "Should contain gte constraint");
		assertTrue(desc.contains("<= 1000000"), "Should contain lte constraint");
	}

	@Test
	void commentWithRequiredConstraint() {
		var schema = ProtobufSchema.generate(TestValidateRequired.getDescriptor());
		var props = props(schema);
		// Comment present, field listed in required array
		assertEquals("Full name, must be provided", field(props, "requiredName").get("description"));
		assertEquals("Nickname, optional", field(props, "optionalName").get("description"));
		@SuppressWarnings("unchecked")
		List<String> required = (List<String>) schema.get("required");
		assertTrue(required.contains("requiredName"));
	}

	@Test
	void commentWithRepeatedConstraints() {
		var props = props(ProtobufSchema.generate(TestValidateRepeated.getDescriptor()));
		var tags = field(props, "tags");
		assertEquals("Labels attached to the resource", tags.get("description"));
		assertEquals(1L, tags.get("minItems"));
		assertEquals(10L, tags.get("maxItems"));
		assertEquals(true, tags.get("uniqueItems"));
	}

	@Test
	void commentWithMapConstraints() {
		var props = props(ProtobufSchema.generate(TestValidateMap.getDescriptor()));
		var metadata = field(props, "metadata");
		assertEquals("Arbitrary key-value metadata", metadata.get("description"));
		assertEquals(1L, metadata.get("minProperties"));
		assertEquals(50L, metadata.get("maxProperties"));
	}

	@Test
	void commentWithEnumConstraint() {
		var props = props(ProtobufSchema.generate(TestValidateEnum.getDescriptor()));
		var priority = field(props, "priority");
		assertEquals("Task priority level", priority.get("description"));
		assertEquals(List.of("PRIORITY_LOW", "PRIORITY_MEDIUM", "PRIORITY_HIGH"), priority.get("enum"));
	}

	@Test
	void generateJsonServesBakedSchemaWithCommentsAndConstraints() {
		// generateJson() returns the schema pre-generated by the protoc plugin into
		// META-INF/buff-json/schema/<fullName>.json — comments (from SourceCodeInfo)
		// and buf.validate constraints are baked in at build time.
		String json = ProtobufSchema.generateJson(TestValidateNumbers.getDescriptor());

		Map<String, Object> parsed = com.alibaba.fastjson2.JSON.parseObject(json);
		assertEquals("Numeric validation constraints", parsed.get("description"));

		// Proto comments baked as field descriptions
		assertTrue(json.contains("Person's age in years"), "field comment baked");
		// Numeric constraints baked as JSON Schema keywords
		assertTrue(json.contains("\"minimum\":0"), "age minimum baked");
		assertTrue(json.contains("\"maximum\":150"), "age maximum baked");
		assertTrue(json.contains("\"exclusiveMaximum\":1000"), "score exclusiveMaximum baked");
		// int64 constraint folded into description text (no JSON Schema numeric
		// equivalent)
		assertTrue(json.contains("Must be <= 1000000."), "int64 constraint in description baked");
	}

	// --- helpers ---

	@SuppressWarnings("unchecked")
	private static Map<String, Object> props(Map<String, Object> schema) {
		return (Map<String, Object>) schema.get("properties");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> field(Map<String, Object> props, String name) {
		return (Map<String, Object>) props.get(name);
	}
}
