package io.suboptimal.buffjson.schema;

import java.util.*;

import com.alibaba.fastjson2.JSON;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.SourceCodeInfo;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Message;

/**
 * Generates JSON Schema (draft 2020-12) from Protocol Buffer message
 * descriptors.
 *
 * <p>
 * The generated schema reflects the
 * <a href="https://protobuf.dev/programming-guides/proto3/#json">Proto3 JSON
 * mapping</a>, matching the JSON representation produced by protobuf's standard
 * JSON serialization. This makes the schemas suitable for OpenAPI 3.1+,
 * AsyncAPI 3.0+, and MCP tool definitions.
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * // From a Descriptor
 * Map<String, Object> schema = ProtobufSchema.generate(MyMessage.getDescriptor());
 *
 * // From a Message class
 * Map<String, Object> schema = ProtobufSchema.generate(MyMessage.class);
 * }</pre>
 *
 * <p>
 * Returns a {@code Map<String, Object>} that can be serialized to JSON with any
 * library (Jackson, Gson, fastjson2) or passed directly to OpenAPI/MCP tooling.
 * For Swagger/OpenAPI integration, see {@code buff-json-swagger} module which
 * provides a {@code ModelConverter} that bridges this output to swagger-core
 * {@code Schema} objects.
 *
 * <p>
 * Handles all proto3 scalar types, enums, nested messages, repeated fields,
 * maps, oneofs, and all 16 protobuf
 * <a href="https://protobuf.dev/reference/protobuf/google.protobuf/">well-known
 * types</a> (Timestamp, Duration, Struct, Value, Any, wrappers, etc.).
 *
 * <p>
 * Recursive and shared message types use {@code $defs}/{@code $ref} to avoid
 * infinite expansion.
 *
 * <p>
 * Proto comments are included as {@code description} fields when source code
 * info is available (requires protoc {@code --include_source_info} flag).
 * Well-known types always include format descriptions regardless.
 *
 * <p>
 * When {@code build.buf:protovalidate} is on the classpath, field-level
 * <a href="https://buf.build/docs/protovalidate/">buf.validate</a> constraints
 * are mapped to JSON Schema validation keywords (e.g. {@code minLength},
 * {@code pattern}, {@code minimum}, {@code format}, {@code required}).
 * Constraints without a direct JSON Schema equivalent are included as
 * human-readable {@code description} text. See {@link ValidateConstraints} for
 * the full mapping table.
 */
public final class ProtobufSchema {

	private static final String SCHEMA_DRAFT = "https://json-schema.org/draft/2020-12/schema";

	/**
	 * Whether {@code build.buf:protovalidate} is on the classpath. When
	 * {@code true}, {@link ValidateConstraints} is used to extract buf.validate
	 * field constraints and merge them into generated schemas. Checked once at
	 * class load time to avoid repeated {@code Class.forName} calls.
	 */
	private static final boolean VALIDATE_AVAILABLE;
	static {
		boolean available;
		try {
			Class.forName("build.buf.validate.ValidateProto");
			available = true;
		} catch (@SuppressWarnings("unused") ClassNotFoundException e) {
			available = false;
		}
		VALIDATE_AVAILABLE = available;
	}

	// Includes google.protobuf.Empty (unlike the serialization modules in
	// buff-json) because schema generation treats it as a WKT with a
	// fixed schema rather than expanding its fields.
	private static final Set<String> WELL_KNOWN_TYPE_NAMES = Set.of("google.protobuf.Any", "google.protobuf.Timestamp",
			"google.protobuf.Duration", "google.protobuf.FieldMask", "google.protobuf.Struct", "google.protobuf.Value",
			"google.protobuf.ListValue", "google.protobuf.DoubleValue", "google.protobuf.FloatValue",
			"google.protobuf.Int64Value", "google.protobuf.UInt64Value", "google.protobuf.Int32Value",
			"google.protobuf.UInt32Value", "google.protobuf.BoolValue", "google.protobuf.StringValue",
			"google.protobuf.BytesValue", "google.protobuf.Empty");

	private final Map<FileDescriptor, Map<List<Integer>, String>> commentCache = new IdentityHashMap<>();
	private final Map<String, Map<String, Object>> defs = new LinkedHashMap<>();
	private final Set<String> inProgress = new HashSet<>();
	private final Descriptor rootDescriptor;

	private ProtobufSchema(Descriptor rootDescriptor) {
		this.rootDescriptor = rootDescriptor;
	}

	/**
	 * Generates a JSON Schema from a protobuf {@link Descriptor}.
	 *
	 * <p>
	 * Proto comments are sourced from the JSON Schema the protoc plugin baked into
	 * {@code META-INF/buff-json/schema/<fullName>.json} (the single home for
	 * comments) — at runtime a compiled descriptor carries no
	 * {@code SourceCodeInfo}, so the live walk has no comments and the
	 * {@code description} fields are overlaid from the baked schema. Only
	 * description strings are copied; every number comes from the live walk,
	 * preserving the map's exact
	 * {@code Integer}/{@code Long}/{@code Float}/{@code Double} types (which a JSON
	 * round-trip could not). When no baked schema is present (e.g. a
	 * {@link com.google.protobuf.DynamicMessage} from a {@code .desc} with
	 * {@code --include_source_info}), comments come from {@code SourceCodeInfo}.
	 *
	 * @param descriptor
	 *            the message descriptor
	 * @return a JSON Schema as a {@code Map<String, Object>}
	 */
	public static Map<String, Object> generate(Descriptor descriptor) {
		ProtobufSchema generator = new ProtobufSchema(descriptor);
		Map<String, Object> schema = generator.schemaForMessage(descriptor);
		schema.put("$schema", SCHEMA_DRAFT);
		if (!generator.defs.isEmpty()) {
			schema.put("$defs", new LinkedHashMap<>(generator.defs));
		}
		String baked = BakedSchema.load(descriptor);
		if (baked != null) {
			overlayDescriptions(schema, JSON.parseObject(baked));
		}
		return schema;
	}

	/**
	 * Copies {@code description} values from a parsed baked schema onto the freshly
	 * generated schema at structurally-matching positions (same map keys / list
	 * indices). Only descriptions are taken from the baked side; every other value
	 * — crucially the numbers, whose boxed types a JSON round-trip would not
	 * preserve — comes from the live schema. Both trees are produced by this same
	 * generator for the same descriptor, so their structure is identical and
	 * positions align; keys present on only one side are simply skipped.
	 */
	@SuppressWarnings("unchecked")
	private static void overlayDescriptions(Object live, Object baked) {
		if (live instanceof Map<?, ?> liveMap && baked instanceof Map<?, ?> bakedMap) {
			Object desc = bakedMap.get("description");
			if (desc instanceof String) {
				((Map<String, Object>) liveMap).put("description", desc);
			}
			for (Map.Entry<?, ?> entry : liveMap.entrySet()) {
				Object bakedChild = bakedMap.get(entry.getKey());
				if (bakedChild != null) {
					overlayDescriptions(entry.getValue(), bakedChild);
				}
			}
		} else if (live instanceof List<?> liveList && baked instanceof List<?> bakedList) {
			for (int i = 0; i < liveList.size() && i < bakedList.size(); i++) {
				overlayDescriptions(liveList.get(i), bakedList.get(i));
			}
		}
	}

	/**
	 * Generates a JSON Schema from a protobuf {@link Message} class.
	 *
	 * @param messageClass
	 *            the message class
	 * @return a JSON Schema as a {@code Map<String, Object>}
	 */
	public static <T extends Message> Map<String, Object> generate(Class<T> messageClass) {
		try {
			Message defaultInstance = (Message) messageClass.getMethod("getDefaultInstance").invoke(null);
			return generate(defaultInstance.getDescriptorForType());
		} catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException("Cannot get descriptor for " + messageClass.getName(), e);
		}
	}

	/**
	 * Returns a JSON Schema as JSON text, either read from a resource pre-generated
	 * by the protoc plugin ({@code META-INF/buff-json/schema/<fullName>.json}) or,
	 * when none is present, serialized from a freshly
	 * {@linkplain #generate(Descriptor) generated} schema.
	 *
	 * <p>
	 * The pre-generated resource carries proto comments and buf.validate
	 * constraints baked in at build time, where {@code SourceCodeInfo} is
	 * available. Serving it as text (rather than parsing it back into a
	 * {@code Map}) preserves the schema's exact numeric forms, which a JSON
	 * round-trip could not.
	 *
	 * @param descriptor
	 *            the message descriptor
	 * @return the JSON Schema as a JSON string
	 */
	public static String generateJson(Descriptor descriptor) {
		String baked = BakedSchema.load(descriptor);
		return baked != null ? baked : JSON.toJSONString(generate(descriptor));
	}

	/**
	 * Returns a JSON Schema as JSON text from a protobuf {@link Message} class. See
	 * {@link #generateJson(Descriptor)}.
	 *
	 * @param messageClass
	 *            the message class
	 * @return the JSON Schema as a JSON string
	 */
	public static <T extends Message> String generateJson(Class<T> messageClass) {
		try {
			Message defaultInstance = (Message) messageClass.getMethod("getDefaultInstance").invoke(null);
			return generateJson(defaultInstance.getDescriptorForType());
		} catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException("Cannot get descriptor for " + messageClass.getName(), e);
		}
	}

	private Map<String, Object> schemaForMessage(Descriptor descriptor) {
		if (WELL_KNOWN_TYPE_NAMES.contains(descriptor.getFullName())) {
			return schemaForWellKnownType(descriptor);
		}

		String fullName = descriptor.getFullName();

		if (inProgress.contains(fullName)) {
			// Cycle detected — placeholder so the $ref resolves after generation completes
			defs.computeIfAbsent(fullName, k -> new LinkedHashMap<>());
			return ref(fullName);
		}

		// Already generated — return $ref
		if (defs.containsKey(fullName)) {
			return ref(fullName);
		}

		inProgress.add(fullName);

		Map<String, Object> properties = new LinkedHashMap<>();
		List<String> requiredFields = new ArrayList<>();
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("title", descriptor.getName());

		String messageComment = getComment(descriptor);
		if (messageComment != null) {
			schema.put("description", messageComment);
		}

		for (FieldDescriptor fd : descriptor.getFields()) {
			Map<String, Object> fieldSchema = schemaForField(fd);
			String fieldComment = getComment(fd);
			if (fieldComment != null) {
				fieldSchema.put("description", fieldComment);
			}

			if (VALIDATE_AVAILABLE) {
				var vc = ValidateConstraints.extract(fd);
				if (vc != null) {
					fieldSchema = applyConstraints(fd, fieldSchema, vc);
					if (vc.required()) {
						requiredFields.add(fd.getJsonName());
					}
					if (vc.descriptionSuffix() != null) {
						String existing = (String) fieldSchema.get("description");
						String desc = existing != null
								? existing + " " + vc.descriptionSuffix()
								: vc.descriptionSuffix();
						fieldSchema.put("description", desc);
					}
				}
			}

			properties.put(fd.getJsonName(), fieldSchema);
		}

		if (!properties.isEmpty()) {
			schema.put("properties", properties);
		}
		if (!requiredFields.isEmpty()) {
			schema.put("required", requiredFields);
		}

		inProgress.remove(fullName);

		// Non-root types always go to $defs; root only if self-referential
		if (descriptor != rootDescriptor || defs.containsKey(fullName)) {
			defs.put(fullName, schema);
			return ref(fullName);
		}

		return schema;
	}

	/** Returns {@code true} if the field is a proto float or double type. */
	private static boolean isFloatType(FieldDescriptor fd) {
		return fd.getJavaType() == FieldDescriptor.JavaType.FLOAT
				|| fd.getJavaType() == FieldDescriptor.JavaType.DOUBLE;
	}

	/**
	 * Merges buf.validate constraints into the field schema.
	 *
	 * <p>
	 * For most field types, constraints are simply merged into the existing schema
	 * map via {@code putAll}. Float/double fields require special handling because
	 * they use a {@code oneOf [number, string]} schema to accommodate NaN/Infinity:
	 * <ul>
	 * <li>If {@code finite} is set, the field cannot be NaN/Infinity, so the
	 * {@code oneOf} is collapsed to a plain {@code {"type": "number"}} with
	 * constraints applied directly.
	 * <li>If not finite but has numeric constraints (e.g. {@code minimum}), the
	 * constraints are placed on the {@code number} branch of the {@code oneOf},
	 * leaving the string branch for NaN/Infinity unchanged.
	 * </ul>
	 *
	 * @param fd
	 *            the field descriptor (used to detect float/double type)
	 * @param fieldSchema
	 *            the base schema produced by {@link #schemaForField}
	 * @param vc
	 *            the extracted validation constraints
	 * @return the schema with constraints applied (may be the same map or a new
	 *         one)
	 */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> applyConstraints(FieldDescriptor fd, Map<String, Object> fieldSchema,
			ValidateConstraints.FieldConstraints vc) {
		if (!isFloatType(fd) || (vc.schemaConstraints().isEmpty() && !vc.finite())) {
			fieldSchema.putAll(vc.schemaConstraints());
			return fieldSchema;
		}

		Map<String, Object> constraints = vc.schemaConstraints();
		Map<String, Object> result;

		if (vc.finite()) {
			result = new LinkedHashMap<>();
			result.put("type", "number");
			result.putAll(constraints);
		} else {
			List<Map<String, Object>> oneOf = (List<Map<String, Object>>) fieldSchema.get("oneOf");
			if (oneOf != null && !oneOf.isEmpty()) {
				Map<String, Object> numberBranch = new LinkedHashMap<>(oneOf.get(0));
				numberBranch.putAll(constraints);
				result = new LinkedHashMap<>();
				result.put("oneOf", List.of(numberBranch, oneOf.get(1)));
			} else {
				fieldSchema.putAll(constraints);
				return fieldSchema;
			}
		}

		// Carry over annotations the base schema already holds — the float/double
		// rebuild above starts from a fresh map, so anything added before
		// applyConstraints (description, and the no-presence "default") must be copied
		// or it is silently dropped.
		Object desc = fieldSchema.get("description");
		if (desc != null) {
			result.put("description", desc);
		}
		Object def = fieldSchema.get("default");
		if (def != null) {
			result.put("default", def);
		}
		return result;
	}

	/** Dispatches to map, repeated, or single-value schema generation. */
	private Map<String, Object> schemaForField(FieldDescriptor fd) {
		if (fd.isMapField()) {
			return schemaForMap(fd);
		}
		if (fd.isRepeated()) {
			Map<String, Object> items = schemaForSingleValue(fd);
			Map<String, Object> schema = new LinkedHashMap<>();
			schema.put("type", "array");
			schema.put("items", items);
			return schema;
		}
		return schemaForSingleValue(fd);
	}

	/** Maps a single (non-repeated, non-map) field to its JSON Schema type. */
	private Map<String, Object> schemaForSingleValue(FieldDescriptor fd) {
		Map<String, Object> schema = switch (fd.getJavaType()) {
			case INT -> schemaForIntField(fd);
			case LONG -> schemaForLongField(fd);
			case FLOAT, DOUBLE -> floatSchema();
			case BOOLEAN -> mapOf("type", "boolean");
			case STRING -> mapOf("type", "string");
			case BYTE_STRING -> mapOf("type", "string", "contentEncoding", "base64");
			case ENUM -> schemaForEnum(fd.getEnumType());
			case MESSAGE -> schemaForMessage(fd.getMessageType());
		};
		// No-presence scalars: an omitted property in proto3 JSON decodes back to the
		// type's zero value, so document that via the JSON Schema "default" annotation.
		// defaultsWhenOmitted excludes repeated/map elements (serialized even at the
		// default) and presence-tracked fields (optional / oneof / message — absent
		// means "unset"), so message fields never reach scalarDefault.
		if (defaultsWhenOmitted(fd)) {
			Object def = scalarDefault(fd);
			if (def != null) {
				schema.put("default", def);
			}
		}
		return schema;
	}

	/**
	 * The proto3 default (zero) value of a no-presence scalar field, in its proto3
	 * JSON representation: {@code 0} for 32-bit ints, the string {@code "0"} for
	 * 64-bit ints (proto3 JSON quotes them), {@code 0.0} for float/double,
	 * {@code false} for bool, {@code ""} for string and bytes, and the zero-value
	 * name for enums. Returns {@code null} for message fields (unreachable — they
	 * have presence) or an enum with no zero value (defensive; proto3 enums always
	 * define one).
	 */
	private static Object scalarDefault(FieldDescriptor fd) {
		return switch (fd.getJavaType()) {
			case INT -> 0;
			case LONG -> "0";
			case FLOAT, DOUBLE -> 0.0;
			case BOOLEAN -> false;
			case STRING, BYTE_STRING -> "";
			case ENUM -> {
				EnumValueDescriptor zero = fd.getEnumType().findValueByNumber(0);
				yield zero != null ? zero.getName() : null;
			}
			case MESSAGE -> null;
		};
	}

	/**
	 * True when an absent field in proto3 JSON is parsed back as the type's zero
	 * value — i.e. a singular, implicit-presence field. Excludes repeated elements
	 * and map values (serialized even at the default) and explicit presence
	 * ({@code optional} / oneof member, where absent means "unset", not the
	 * default).
	 */
	private static boolean defaultsWhenOmitted(FieldDescriptor fd) {
		return !fd.isRepeated() && !fd.hasPresence() && !fd.getContainingType().getOptions().getMapEntry();
	}

	private static Map<String, Object> schemaForIntField(FieldDescriptor fd) {
		var type = fd.getType();
		if (type == FieldDescriptor.Type.UINT32 || type == FieldDescriptor.Type.FIXED32) {
			Map<String, Object> schema = new LinkedHashMap<>();
			schema.put("type", "integer");
			schema.put("minimum", 0);
			return schema;
		}
		return mapOf("type", "integer");
	}

	private static Map<String, Object> schemaForLongField(FieldDescriptor fd) {
		var type = fd.getType();
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "string");
		if (type == FieldDescriptor.Type.UINT64 || type == FieldDescriptor.Type.FIXED64) {
			schema.put("format", "uint64");
		} else {
			schema.put("format", "int64");
		}
		return schema;
	}

	/**
	 * Returns the {@code oneOf [number, string]} schema for float/double fields.
	 * The string branch allows NaN, Infinity, and -Infinity as per proto3 JSON
	 * spec.
	 */
	private static Map<String, Object> floatSchema() {
		Map<String, Object> number = mapOf("type", "number");
		Map<String, Object> special = new LinkedHashMap<>();
		special.put("type", "string");
		special.put("enum", List.of("NaN", "Infinity", "-Infinity"));
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("oneOf", List.of(number, special));
		return schema;
	}

	private Map<String, Object> schemaForEnum(EnumDescriptor enumDesc) {
		String fullName = enumDesc.getFullName();

		// Already generated — return $ref
		if (defs.containsKey(fullName)) {
			return ref(fullName);
		}

		List<String> names = new ArrayList<>();
		for (EnumValueDescriptor value : enumDesc.getValues()) {
			names.add(value.getName());
		}
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "string");
		schema.put("title", enumDesc.getName());
		schema.put("enum", names);

		String enumComment = getComment(enumDesc);
		if (enumComment != null) {
			schema.put("description", enumComment);
		}

		defs.put(fullName, schema);
		return ref(fullName);
	}

	private Map<String, Object> schemaForMap(FieldDescriptor fd) {
		Descriptor entryDesc = fd.getMessageType();
		FieldDescriptor valueDesc = entryDesc.findFieldByName("value");
		Map<String, Object> valueSchema = schemaForSingleValue(valueDesc);
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("additionalProperties", valueSchema);
		return schema;
	}

	/**
	 * Returns the canonical JSON Schema for a protobuf well-known type. Each WKT
	 * has a fixed schema defined by the proto3 JSON spec, independent of the
	 * message's fields.
	 */
	private static Map<String, Object> schemaForWellKnownType(Descriptor descriptor) {
		return switch (descriptor.getFullName()) {
			case "google.protobuf.Timestamp" -> {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("type", "string");
				s.put("format", "date-time");
				s.put("description", "RFC 3339 date-time format.");
				yield s;
			}
			case "google.protobuf.Duration" -> {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("type", "string");
				s.put("description", "Signed seconds with up to 9 fractional digits, suffixed with 's'.");
				yield s;
			}
			case "google.protobuf.FieldMask" -> {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("type", "string");
				s.put("description", "Comma-separated camelCase field paths.");
				yield s;
			}
			case "google.protobuf.Struct" -> {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("type", "object");
				s.put("description", "Arbitrary JSON object.");
				yield s;
			}
			case "google.protobuf.Value" -> {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("description", "Arbitrary JSON value.");
				yield s;
			}
			case "google.protobuf.ListValue" -> {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("type", "array");
				s.put("description", "JSON array of arbitrary values.");
				yield s;
			}
			case "google.protobuf.Empty" -> mapOf("type", "object");
			case "google.protobuf.Any" -> {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("type", "object");
				s.put("properties", Map.of("@type", mapOf("type", "string")));
				s.put("required", List.of("@type"));
				s.put("description", "Arbitrary message identified by a type URL in @type.");
				yield s;
			}
			case "google.protobuf.Int32Value" -> mapOf("type", "integer");
			case "google.protobuf.UInt32Value" -> {
				Map<String, Object> s = new LinkedHashMap<>();
				s.put("type", "integer");
				s.put("minimum", 0);
				yield s;
			}
			case "google.protobuf.Int64Value" -> mapOf("type", "string", "format", "int64");
			case "google.protobuf.UInt64Value" -> mapOf("type", "string", "format", "uint64");
			case "google.protobuf.FloatValue", "google.protobuf.DoubleValue" -> floatSchema();
			case "google.protobuf.BoolValue" -> mapOf("type", "boolean");
			case "google.protobuf.StringValue" -> mapOf("type", "string");
			case "google.protobuf.BytesValue" -> mapOf("type", "string", "contentEncoding", "base64");
			default -> throw new IllegalArgumentException("Unknown well-known type: " + descriptor.getFullName());
		};
	}

	private String getComment(Descriptor descriptor) {
		List<Integer> path = getMessagePath(descriptor);
		return lookupComment(descriptor.getFile(), path);
	}

	private String getComment(FieldDescriptor fd) {
		Descriptor parent = fd.getContainingType();
		List<Integer> parentPath = getMessagePath(parent);
		List<Integer> path = new ArrayList<>(parentPath);
		path.add(DescriptorProto.FIELD_FIELD_NUMBER);
		path.add(fd.getIndex());
		return lookupComment(fd.getFile(), path);
	}

	private String getComment(EnumDescriptor enumDesc) {
		if (enumDesc.getContainingType() != null) {
			List<Integer> parentPath = getMessagePath(enumDesc.getContainingType());
			List<Integer> path = new ArrayList<>(parentPath);
			path.add(DescriptorProto.ENUM_TYPE_FIELD_NUMBER);
			path.add(enumDesc.getIndex());
			return lookupComment(enumDesc.getFile(), path);
		}
		return lookupComment(enumDesc.getFile(),
				List.of(FileDescriptorProto.ENUM_TYPE_FIELD_NUMBER, enumDesc.getIndex()));
	}

	private static List<Integer> getMessagePath(Descriptor descriptor) {
		Deque<Integer> indices = new ArrayDeque<>();
		Descriptor current = descriptor;
		while (current != null) {
			indices.addFirst(current.getIndex());
			if (current.getContainingType() != null) {
				indices.addFirst(DescriptorProto.NESTED_TYPE_FIELD_NUMBER);
			} else {
				indices.addFirst(FileDescriptorProto.MESSAGE_TYPE_FIELD_NUMBER);
			}
			current = current.getContainingType();
		}
		return new ArrayList<>(indices);
	}

	// Builds and caches a path→comment index per file on first access
	private String lookupComment(FileDescriptor file, List<Integer> path) {
		Map<List<Integer>, String> index = commentCache.computeIfAbsent(file, ProtobufSchema::buildCommentIndex);
		return index.get(path);
	}

	private static Map<List<Integer>, String> buildCommentIndex(FileDescriptor file) {
		FileDescriptorProto proto = file.toProto();
		if (!proto.hasSourceCodeInfo()) {
			return Map.of();
		}
		SourceCodeInfo info = proto.getSourceCodeInfo();
		Map<List<Integer>, String> index = new HashMap<>();
		for (SourceCodeInfo.Location loc : info.getLocationList()) {
			if (loc.hasLeadingComments()) {
				String comment = stripCommentLines(loc.getLeadingComments());
				if (!comment.isEmpty()) {
					index.put(loc.getPathList(), comment);
				}
			}
		}
		return index;
	}

	/**
	 * Cleans a raw {@code leading_comments} string: trims each line, drops the
	 * {@code * } / {@code *} prefix that protoc keeps for
	 * {@code /** ... *}{@code /} block comments, removes blank lines, and joins the
	 * rest with newlines.
	 */
	private static String stripCommentLines(String comment) {
		StringBuilder sb = new StringBuilder();
		for (String line : comment.split("\n")) {
			String stripped = line.strip();
			if (stripped.startsWith("* ")) {
				stripped = stripped.substring(2);
			} else if (stripped.equals("*")) {
				stripped = "";
			}
			if (!stripped.isEmpty()) {
				if (!sb.isEmpty()) {
					sb.append('\n');
				}
				sb.append(stripped);
			}
		}
		return sb.toString();
	}

	private static Map<String, Object> ref(String fullName) {
		return mapOf("$ref", "#/$defs/" + fullName);
	}

	private static Map<String, Object> mapOf(String key, Object value) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put(key, value);
		return map;
	}

	private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put(k1, v1);
		map.put(k2, v2);
		return map;
	}
}
