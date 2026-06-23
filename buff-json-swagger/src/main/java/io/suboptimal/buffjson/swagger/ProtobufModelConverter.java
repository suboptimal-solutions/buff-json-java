package io.suboptimal.buffjson.swagger;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;

import io.suboptimal.buffjson.schema.ProtobufSchema;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.oas.models.media.Schema;

/**
 * Swagger/OpenAPI {@link ModelConverter} that resolves Protocol Buffer
 * {@link Message} types to OpenAPI schemas using {@link ProtobufSchema}.
 *
 * <p>
 * Targets OpenAPI 3.1, which is natively compatible with JSON Schema draft
 * 2020-12 produced by {@link ProtobufSchema#generate}.
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * ModelConverters.getInstance(true).addConverter(new ProtobufModelConverter());
 * }</pre>
 */
public class ProtobufModelConverter implements ModelConverter {

	private static final String COMPONENTS_SCHEMAS_REF = "#/components/schemas/";
	private static final String DEFS_REF = "#/$defs/";

	@Override
	public Schema<?> resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
		Class<?> rawClass = rawClass(type.getType());
		if (rawClass == null || !Message.class.isAssignableFrom(rawClass)) {
			return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
		}

		Descriptor descriptor = descriptorFor(rawClass);
		Map<String, Object> jsonSchema = ProtobufSchema.generate(descriptor);

		// Strip $schema — not needed in OpenAPI
		jsonSchema.remove("$schema");

		// Extract $defs and register each as a named schema in the context
		@SuppressWarnings("unchecked")
		Map<String, Map<String, Object>> defs = (Map<String, Map<String, Object>>) jsonSchema.remove("$defs");
		if (defs != null) {
			for (var entry : defs.entrySet()) {
				Schema<?> defSchema = convertMapToSchema(entry.getValue());
				defSchema.setName(entry.getKey());
				context.defineModel(entry.getKey(), defSchema);
			}
		}

		Schema<?> schema = convertMapToSchema(jsonSchema);
		String fullName = descriptor.getFullName();
		boolean rootIsRef = schema.get$ref() != null;

		if (type.isResolveAsRef()) {
			if (!rootIsRef) {
				schema.setName(fullName);
				context.defineModel(fullName, schema);
			}
			// Nameless $ref is load-bearing: ModelConverterContextImpl.resolve()
			// auto-registers the returned schema into modelByName[getName()] when
			// the name is non-null. A named ref would overwrite the full schema
			// with a self-referential ref. Matches swagger-core ModelResolver.
			return new Schema<>().$ref(COMPONENTS_SCHEMAS_REF + fullName);
		}

		if (!rootIsRef) {
			schema.setName(fullName);
		}

		return schema;
	}

	@Override
	public boolean isOpenapi31() {
		return true;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static Schema<?> convertMapToSchema(Map<String, Object> map) {
		Schema schema = new Schema<>();

		for (var entry : map.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();

			switch (key) {
				case "type" -> {
					String t = (String) value;
					schema.setType(t);
					schema.addType(t);
				}
				case "format" -> schema.setFormat((String) value);
				case "title" -> schema.setTitle((String) value);
				case "description" -> schema.setDescription((String) value);
				case "default" -> schema.setDefault(value);
				case "enum" -> schema.setEnum((List) value);
				case "const" -> schema.setConst(value);
				case "pattern" -> schema.setPattern((String) value);
				case "contentEncoding" -> schema.setContentEncoding((String) value);
				case "minimum" -> schema.setMinimum(toBigDecimal(value));
				case "maximum" -> schema.setMaximum(toBigDecimal(value));
				case "exclusiveMinimum" -> schema.setExclusiveMinimumValue(toBigDecimal(value));
				case "exclusiveMaximum" -> schema.setExclusiveMaximumValue(toBigDecimal(value));
				case "minLength" -> schema.setMinLength(toInteger(value));
				case "maxLength" -> schema.setMaxLength(toInteger(value));
				case "minItems" -> schema.setMinItems(toInteger(value));
				case "maxItems" -> schema.setMaxItems(toInteger(value));
				case "uniqueItems" -> schema.setUniqueItems((Boolean) value);
				case "minProperties" -> schema.setMinProperties(toInteger(value));
				case "maxProperties" -> schema.setMaxProperties(toInteger(value));
				case "required" -> schema.setRequired((List<String>) value);
				case "items" -> schema.setItems(convertMapToSchema((Map<String, Object>) value));
				case "additionalProperties" ->
					schema.setAdditionalProperties(convertMapToSchema((Map<String, Object>) value));
				case "properties" -> {
					Map<String, Object> props = (Map<String, Object>) value;
					Map<String, Schema> converted = new LinkedHashMap<>();
					for (var prop : props.entrySet()) {
						converted.put(prop.getKey(), convertMapToSchema((Map<String, Object>) prop.getValue()));
					}
					schema.setProperties(converted);
				}
				case "oneOf" -> {
					List<Map<String, Object>> oneOfList = (List<Map<String, Object>>) value;
					List<Schema> converted = new ArrayList<>();
					for (Map<String, Object> item : oneOfList) {
						converted.add(convertMapToSchema(item));
					}
					schema.setOneOf(converted);
				}
				case "$ref" -> {
					String ref = (String) value;
					if (ref.startsWith(DEFS_REF)) {
						schema.set$ref(COMPONENTS_SCHEMAS_REF + ref.substring(DEFS_REF.length()));
					} else {
						schema.set$ref(ref);
					}
				}
				default -> {
				}
			}
		}
		return schema;
	}

	private static Descriptor descriptorFor(Class<?> messageClass) {
		try {
			Message defaultInstance = (Message) messageClass.getMethod("getDefaultInstance").invoke(null);
			return defaultInstance.getDescriptorForType();
		} catch (ReflectiveOperationException e) {
			throw new IllegalArgumentException("Cannot get descriptor for " + messageClass.getName(), e);
		}
	}

	private static Class<?> rawClass(Type type) {
		if (type instanceof Class<?> c) {
			return c;
		}
		if (type instanceof ParameterizedType pt) {
			Type raw = pt.getRawType();
			return raw instanceof Class<?> c ? c : null;
		}
		return null;
	}

	private static BigDecimal toBigDecimal(Object value) {
		if (value instanceof BigDecimal bd) {
			return bd;
		}
		if (value instanceof Double || value instanceof Float) {
			return BigDecimal.valueOf(((Number) value).doubleValue());
		}
		return BigDecimal.valueOf(((Number) value).longValue());
	}

	private static Integer toInteger(Object value) {
		return ((Number) value).intValue();
	}
}
