package io.suboptimal.buffjson.schema;

import java.util.*;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import build.buf.validate.FieldRules;
import build.buf.validate.StringRules;
import build.buf.validate.ValidateProto;

/**
 * Extracts <a href="https://buf.build/docs/protovalidate/">buf.validate</a>
 * (protovalidate) constraints from protobuf field descriptors and maps them to
 * <a href="https://json-schema.org/draft/2020-12/schema">JSON Schema (draft
 * 2020-12)</a> keywords.
 *
 * <p>
 * This class is only loaded when {@code build.buf:protovalidate} is on the
 * classpath. {@link ProtobufSchema} guards access with a {@code Class.forName}
 * check so that schema generation works without the dependency.
 *
 * <h3>Constraint mapping strategy</h3>
 *
 * Constraints fall into three categories:
 * <ol>
 * <li><b>Direct JSON Schema keywords</b> — e.g. {@code string.min_len} →
 * {@code minLength}, {@code int32.gte} → {@code minimum},
 * {@code repeated.unique} → {@code uniqueItems}. These are placed in
 * {@link FieldConstraints#schemaConstraints()}.
 * <li><b>Description-only</b> — constraints with no JSON Schema equivalent
 * (e.g. {@code prefix}, {@code suffix}, {@code not_in}, CEL expressions). These
 * are rendered as human-readable text in
 * {@link FieldConstraints#descriptionSuffix()}.
 * <li><b>64-bit numeric constraints</b> — int64/uint64 are represented as JSON
 * strings in proto3 JSON, so numeric keywords like {@code minimum} don't apply.
 * All constraints for 64-bit types go to description text.
 * </ol>
 *
 * <h3>Float/double special handling</h3>
 *
 * Float/double fields use a {@code oneOf [number, string]} schema to support
 * NaN/Infinity. The {@link FieldConstraints#finite()} flag signals that the
 * {@code finite} constraint is set, allowing {@link ProtobufSchema} to collapse
 * the {@code oneOf} to a plain {@code {"type": "number"}} with constraints.
 * When not finite, numeric constraints are placed on the {@code number} branch
 * of the {@code oneOf}.
 *
 * @see ProtobufSchema#applyConstraints
 */
final class ValidateConstraints {

	/**
	 * Result of extracting buf.validate constraints for a single protobuf field.
	 *
	 * @param schemaConstraints
	 *            JSON Schema keywords to merge into the field schema (e.g.
	 *            {@code "minLength"} → 3, {@code "pattern"} → {@code "^[a-z]+$"})
	 * @param required
	 *            {@code true} if {@code (buf.validate.field).required = true} — the
	 *            field name should be added to the parent object's {@code required}
	 *            array
	 * @param finite
	 *            {@code true} if a float/double field has
	 *            {@code (buf.validate.field).float.finite = true}, signaling that
	 *            the {@code oneOf [number, string]} schema can be collapsed to
	 *            plain {@code number}
	 * @param descriptionSuffix
	 *            human-readable text describing constraints that have no direct
	 *            JSON Schema keyword (appended to the field's {@code description}),
	 *            or {@code null} if none
	 */
	record FieldConstraints(Map<String, Object> schemaConstraints, boolean required, boolean finite,
			String descriptionSuffix) {
	}

	/**
	 * Extracts buf.validate constraints from a protobuf field descriptor and
	 * returns the corresponding JSON Schema keywords and metadata.
	 *
	 * <p>
	 * Reads the {@code (buf.validate.field)} extension from the field's options. If
	 * the extension is absent, unset, or the field has no constraints, returns
	 * {@code null}. Otherwise dispatches to type-specific extraction methods based
	 * on {@link FieldRules#getTypeCase()}.
	 *
	 * @param fd
	 *            the protobuf field descriptor to extract constraints from
	 * @return extracted constraints, or {@code null} if no constraints are present
	 */
	static FieldConstraints extract(FieldDescriptor fd) {
		FieldRules rules;
		try {
			rules = fd.getOptions().getExtension(ValidateProto.field);
		} catch (@SuppressWarnings("unused") Exception e) {
			return null;
		}
		// Cheaper than rules.equals(getDefaultInstance()) — avoids deep message
		// comparison by checking the three top-level indicators of constraint presence
		if (rules == null || (rules.getTypeCase() == FieldRules.TypeCase.TYPE_NOT_SET && !rules.hasRequired()
				&& rules.getCelList().isEmpty())) {
			return null;
		}

		Map<String, Object> constraints = new LinkedHashMap<>();
		List<String> descriptions = new ArrayList<>();
		boolean required = rules.hasRequired() && rules.getRequired();
		boolean finite = false;

		switch (rules.getTypeCase()) {
			case STRING -> extractStringRules(rules.getString(), constraints, descriptions);
			case FLOAT -> finite = extractNumericRules(rules.getFloat(), constraints, descriptions);
			case DOUBLE -> finite = extractNumericRules(rules.getDouble(), constraints, descriptions);
			case INT32 -> extractNumericRules(rules.getInt32(), constraints, descriptions);
			case INT64 -> extractNumericDescriptionOnly(rules.getInt64(), descriptions);
			case UINT32 -> extractNumericRules(rules.getUint32(), constraints, descriptions);
			case UINT64 -> extractNumericDescriptionOnly(rules.getUint64(), descriptions);
			case SINT32 -> extractNumericRules(rules.getSint32(), constraints, descriptions);
			case SINT64 -> extractNumericDescriptionOnly(rules.getSint64(), descriptions);
			case FIXED32 -> extractNumericRules(rules.getFixed32(), constraints, descriptions);
			case FIXED64 -> extractNumericDescriptionOnly(rules.getFixed64(), descriptions);
			case SFIXED32 -> extractNumericRules(rules.getSfixed32(), constraints, descriptions);
			case SFIXED64 -> extractNumericDescriptionOnly(rules.getSfixed64(), descriptions);
			case BOOL -> extractBoolRules(rules.getBool(), constraints);
			case BYTES -> extractBytesRules(rules.getBytes(), descriptions);
			case ENUM -> extractEnumRules(rules.getEnum(), fd, constraints, descriptions);
			case REPEATED -> extractRepeatedRules(rules.getRepeated(), constraints);
			case MAP -> extractMapRules(rules.getMap(), constraints);
			default -> {
			}
		}

		// CEL expressions have no JSON Schema equivalent — include as description text
		for (var cel : rules.getCelList()) {
			if (cel.hasMessage() && !cel.getMessage().isEmpty()) {
				descriptions.add("Validation: " + cel.getMessage());
			} else if (cel.hasExpression() && !cel.getExpression().isEmpty()) {
				descriptions.add("Validation: " + cel.getExpression());
			}
		}

		if (constraints.isEmpty() && !required && !finite && descriptions.isEmpty()) {
			return null;
		}

		String descSuffix = descriptions.isEmpty() ? null : String.join(". ", descriptions) + ".";
		return new FieldConstraints(constraints, required, finite, descSuffix);
	}

	/**
	 * Maps {@link StringRules} to JSON Schema keywords.
	 *
	 * <p>
	 * Direct mappings: {@code const}, {@code len} (→ minLength + maxLength),
	 * {@code min_len} (→ minLength), {@code max_len} (→ maxLength),
	 * {@code pattern}, {@code in} (→ enum). Well-known format validators (email,
	 * hostname, uri, uuid, etc.) map to the JSON Schema {@code format} keyword.
	 * Constraints without a JSON Schema equivalent (prefix, suffix, contains,
	 * not_contains, not_in, byte length) go to description text.
	 */
	private static void extractStringRules(StringRules rules, Map<String, Object> constraints,
			List<String> descriptions) {
		if (rules.hasConst()) {
			constraints.put("const", rules.getConst());
		}
		if (rules.hasLen()) {
			constraints.put("minLength", rules.getLen());
			constraints.put("maxLength", rules.getLen());
		}
		if (rules.hasMinLen()) {
			constraints.put("minLength", rules.getMinLen());
		}
		if (rules.hasMaxLen()) {
			constraints.put("maxLength", rules.getMaxLen());
		}
		if (rules.hasPattern()) {
			constraints.put("pattern", rules.getPattern());
		}
		if (rules.getInCount() > 0) {
			constraints.put("enum", List.copyOf(rules.getInList()));
		}
		if (rules.getNotInCount() > 0) {
			descriptions.add("Must not be one of: " + rules.getNotInList());
		}
		if (rules.hasPrefix()) {
			descriptions.add("Must start with \"" + rules.getPrefix() + "\"");
		}
		if (rules.hasSuffix()) {
			descriptions.add("Must end with \"" + rules.getSuffix() + "\"");
		}
		if (rules.hasContains()) {
			descriptions.add("Must contain \"" + rules.getContains() + "\"");
		}
		if (rules.hasNotContains()) {
			descriptions.add("Must not contain \"" + rules.getNotContains() + "\"");
		}
		if (rules.hasMinBytes()) {
			descriptions.add("Minimum " + rules.getMinBytes() + " bytes");
		}
		if (rules.hasMaxBytes()) {
			descriptions.add("Maximum " + rules.getMaxBytes() + " bytes");
		}

		// Well-known format validators → JSON Schema "format" keyword where a
		// standard format exists, otherwise description text
		switch (rules.getWellKnownCase()) {
			case EMAIL -> constraints.put("format", "email");
			case HOSTNAME -> constraints.put("format", "hostname");
			case IP -> constraints.put("format", "ip");
			case IPV4 -> constraints.put("format", "ipv4");
			case IPV6 -> constraints.put("format", "ipv6");
			case URI -> constraints.put("format", "uri");
			case URI_REF -> constraints.put("format", "uri-reference");
			case UUID -> constraints.put("format", "uuid");
			case TUUID -> descriptions.add("Must be a trimmed UUID (no hyphens)");
			case ADDRESS -> constraints.put("format", "hostname");
			case IP_WITH_PREFIXLEN -> descriptions.add("Must be an IP with prefix length");
			case IPV4_WITH_PREFIXLEN -> descriptions.add("Must be an IPv4 with prefix length");
			case IPV6_WITH_PREFIXLEN -> descriptions.add("Must be an IPv6 with prefix length");
			case IP_PREFIX -> descriptions.add("Must be an IP prefix");
			case IPV4_PREFIX -> descriptions.add("Must be an IPv4 prefix");
			case IPV6_PREFIX -> descriptions.add("Must be an IPv6 prefix");
			case HOST_AND_PORT -> descriptions.add("Must be a host and port");
			case ULID -> descriptions.add("Must be a ULID");
			case WELL_KNOWN_REGEX -> descriptions.add("Must match well-known regex pattern");
			default -> {
			}
		}
	}

	/**
	 * Maps numeric constraints to JSON Schema keywords using protobuf descriptor
	 * reflection. Works for all numeric rule types (Int32Rules, UInt32Rules,
	 * FloatRules, DoubleRules, etc.) since they share identical field names.
	 *
	 * <p>
	 * Mappings: {@code const} → {@code const}, {@code gt} →
	 * {@code exclusiveMinimum}, {@code gte} → {@code minimum}, {@code lt} →
	 * {@code exclusiveMaximum}, {@code lte} → {@code maximum}, {@code in} →
	 * {@code enum}. The {@code not_in} constraint has no JSON Schema equivalent and
	 * goes to description text.
	 *
	 * @return {@code true} if the {@code finite} constraint is set (only meaningful
	 *         for float/double rules)
	 */
	private static boolean extractNumericRules(Message rules, Map<String, Object> constraints,
			List<String> descriptions) {
		var desc = rules.getDescriptorForType();

		extractNumericField(rules, desc, "const", "const", constraints);
		extractNumericField(rules, desc, "gt", "exclusiveMinimum", constraints);
		extractNumericField(rules, desc, "gte", "minimum", constraints);
		extractNumericField(rules, desc, "lt", "exclusiveMaximum", constraints);
		extractNumericField(rules, desc, "lte", "maximum", constraints);

		List<?> inValues = collectRepeatedField(rules, desc, "in");
		if (!inValues.isEmpty()) {
			constraints.put("enum", inValues);
		}

		List<?> notInValues = collectRepeatedField(rules, desc, "not_in");
		if (!notInValues.isEmpty()) {
			descriptions.add("Must not be one of: " + notInValues);
		}

		var finiteField = desc.findFieldByName("finite");
		return finiteField != null && rules.hasField(finiteField) && (boolean) rules.getField(finiteField);
	}

	/**
	 * Renders all numeric constraints as description text for 64-bit types. In
	 * proto3 JSON, int64/uint64/sint64/sfixed64/fixed64 are represented as strings,
	 * so numeric JSON Schema keywords ({@code minimum}, {@code maximum}, etc.)
	 * don't apply to the string representation.
	 */
	private static void extractNumericDescriptionOnly(Message rules, List<String> descriptions) {
		var desc = rules.getDescriptorForType();

		appendNumericDesc(rules, desc, "const", "Must equal %s", descriptions);
		appendNumericDesc(rules, desc, "gt", "Must be > %s", descriptions);
		appendNumericDesc(rules, desc, "gte", "Must be >= %s", descriptions);
		appendNumericDesc(rules, desc, "lt", "Must be < %s", descriptions);
		appendNumericDesc(rules, desc, "lte", "Must be <= %s", descriptions);

		List<?> inValues = collectRepeatedField(rules, desc, "in");
		if (!inValues.isEmpty()) {
			descriptions.add("Must be one of: " + inValues);
		}

		List<?> notInValues = collectRepeatedField(rules, desc, "not_in");
		if (!notInValues.isEmpty()) {
			descriptions.add("Must not be one of: " + notInValues);
		}
	}

	/** Maps {@code BoolRules.const} to JSON Schema {@code const}. */
	private static void extractBoolRules(Message rules, Map<String, Object> constraints) {
		var desc = rules.getDescriptorForType();
		var constField = desc.findFieldByName("const");
		if (constField != null && rules.hasField(constField)) {
			constraints.put("const", rules.getField(constField));
		}
	}

	/**
	 * Renders bytes constraints as description text. Bytes are base64-encoded
	 * strings in proto3 JSON, so byte length constraints don't map directly to JSON
	 * string length.
	 */
	private static void extractBytesRules(Message rules, List<String> descriptions) {
		var desc = rules.getDescriptorForType();
		appendNumericDesc(rules, desc, "min_len", "Minimum %s bytes", descriptions);
		appendNumericDesc(rules, desc, "max_len", "Maximum %s bytes", descriptions);
		appendNumericDesc(rules, desc, "len", "Exactly %s bytes", descriptions);

		var patternField = desc.findFieldByName("pattern");
		if (patternField != null && rules.hasField(patternField)) {
			descriptions.add("Must match pattern: " + rules.getField(patternField));
		}

		var constField = desc.findFieldByName("const");
		if (constField != null && rules.hasField(constField)) {
			descriptions.add("Must equal a specific byte sequence");
		}

		if (!collectRepeatedField(rules, desc, "in").isEmpty()) {
			descriptions.add("Must be one of specific byte sequences");
		}
	}

	/**
	 * Maps enum constraints to JSON Schema. {@code const} maps to a single enum
	 * name, {@code in} filters the {@code enum} list to allowed values,
	 * {@code not_in} goes to description. Enum values are resolved from their
	 * numeric proto values to string names via the field's
	 * {@link EnumValueDescriptor}.
	 */
	private static void extractEnumRules(Message rules, FieldDescriptor fd, Map<String, Object> constraints,
			List<String> descriptions) {
		var desc = rules.getDescriptorForType();
		var enumDesc = fd.getEnumType();

		var constField = desc.findFieldByName("const");
		if (constField != null && rules.hasField(constField)) {
			int constValue = (int) rules.getField(constField);
			EnumValueDescriptor evd = enumDesc.findValueByNumber(constValue);
			if (evd != null) {
				constraints.put("const", evd.getName());
			}
		}

		List<?> inValues = collectRepeatedField(rules, desc, "in");
		if (!inValues.isEmpty()) {
			List<String> allowed = new ArrayList<>();
			for (Object v : inValues) {
				EnumValueDescriptor evd = enumDesc.findValueByNumber((int) v);
				if (evd != null) {
					allowed.add(evd.getName());
				}
			}
			if (!allowed.isEmpty()) {
				constraints.put("enum", allowed);
			}
		}

		List<?> notInValues = collectRepeatedField(rules, desc, "not_in");
		if (!notInValues.isEmpty()) {
			List<String> forbidden = new ArrayList<>();
			for (Object v : notInValues) {
				EnumValueDescriptor evd = enumDesc.findValueByNumber((int) v);
				if (evd != null) {
					forbidden.add(evd.getName());
				}
			}
			if (!forbidden.isEmpty()) {
				descriptions.add("Must not be one of: " + forbidden);
			}
		}
	}

	/**
	 * Maps repeated field constraints: {@code min_items} → {@code minItems},
	 * {@code max_items} → {@code maxItems}, {@code unique} → {@code uniqueItems}.
	 */
	private static void extractRepeatedRules(Message rules, Map<String, Object> constraints) {
		var desc = rules.getDescriptorForType();
		extractNumericField(rules, desc, "min_items", "minItems", constraints);
		extractNumericField(rules, desc, "max_items", "maxItems", constraints);

		var uniqueField = desc.findFieldByName("unique");
		if (uniqueField != null && rules.hasField(uniqueField) && (boolean) rules.getField(uniqueField)) {
			constraints.put("uniqueItems", true);
		}
	}

	/**
	 * Maps map field constraints: {@code min_pairs} → {@code minProperties},
	 * {@code max_pairs} → {@code maxProperties}.
	 */
	private static void extractMapRules(Message rules, Map<String, Object> constraints) {
		var desc = rules.getDescriptorForType();
		extractNumericField(rules, desc, "min_pairs", "minProperties", constraints);
		extractNumericField(rules, desc, "max_pairs", "maxProperties", constraints);
	}

	// --- helpers ---

	/**
	 * Collects all values of a repeated protobuf field by name. Returns the
	 * protobuf-internal immutable list directly (no copy) since the values are only
	 * read, not modified. Returns an empty list if the field doesn't exist or has
	 * no values.
	 */
	@SuppressWarnings("unchecked")
	private static List<?> collectRepeatedField(Message rules, com.google.protobuf.Descriptors.Descriptor desc,
			String fieldName) {
		var fd = desc.findFieldByName(fieldName);
		if (fd == null || rules.getRepeatedFieldCount(fd) == 0) {
			return List.of();
		}
		return (List<Object>) rules.getField(fd);
	}

	/**
	 * Reads a single scalar field by name from a protobuf rule message and puts it
	 * into the constraints map under the given JSON Schema key. No-op if the field
	 * doesn't exist or isn't set.
	 */
	private static void extractNumericField(Message rules, com.google.protobuf.Descriptors.Descriptor desc,
			String protoField, String jsonSchemaKey, Map<String, Object> constraints) {
		var fd = desc.findFieldByName(protoField);
		if (fd != null && rules.hasField(fd)) {
			constraints.put(jsonSchemaKey, rules.getField(fd));
		}
	}

	/**
	 * Reads a single scalar field by name and appends a formatted description
	 * string. Used for constraints that have no JSON Schema keyword equivalent.
	 */
	private static void appendNumericDesc(Message rules, com.google.protobuf.Descriptors.Descriptor desc,
			String protoField, String format, List<String> descriptions) {
		var fd = desc.findFieldByName(protoField);
		if (fd != null && rules.hasField(fd)) {
			descriptions.add(String.format(format, rules.getField(fd)));
		}
	}
}
