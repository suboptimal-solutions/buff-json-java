package io.suboptimal.buffjson.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSONReader;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.Message;

/**
 * Cached public builder setters for concrete protobuf messages. Method handles
 * retain primitive parameter types; ordinary Java lambdas are compiled ahead of
 * time, so no runtime class generation is needed in GraalVM native images.
 * Unsupported fields retain the descriptor-based implementation individually.
 */
public final class TypedMessageReaderSchema {

	private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
	private static final ClassValue<TypedMessageReaderSchema> CACHE = new ClassValue<>() {
		@Override
		protected TypedMessageReaderSchema computeValue(Class<?> type) {
			return new TypedMessageReaderSchema(ProtobufMessageReader.getDefaultInstance(type));
		}
	};

	private final Map<String, Field> fields;
	private final NameTable names;
	private final int typedFieldCount;

	private TypedMessageReaderSchema(Message defaultInstance) {
		var descriptors = defaultInstance.getDescriptorForType().getFields();
		fields = new HashMap<>(descriptors.size() * 2);
		Class<?> messageClass = defaultInstance.getClass();
		Class<?> builderClass = defaultInstance.newBuilderForType().getClass();
		int count = 0;
		for (FieldDescriptor fd : descriptors) {
			Parser parser;
			try {
				parser = create(fd, messageClass, builderClass);
				count++;
			} catch (ReflectiveOperationException | IllegalArgumentException e) {
				parser = fallback(fd);
			}
			Field field = new Field(fd, parser, FieldNameMatcher.of(fd.getJsonName()));
			fields.put(fd.getJsonName(), field);
			fields.put(fd.getName(), field);
		}
		names = new NameTable(fields);
		typedFieldCount = count;
	}

	public static TypedMessageReaderSchema forMessage(Message message) {
		return message instanceof GeneratedMessage ? CACHE.get(message.getClass()) : null;
	}

	/** Number of fields with bound setters; useful for verifying path coverage. */
	public int typedFieldCount() {
		return typedFieldCount;
	}

	void readFields(JSONReader reader, Message.Builder builder, ProtobufMessageReader messageReader) {
		while (!reader.nextIfObjectEnd()) {
			// Common spelling first: byte-exact match of "jsonName": consumes the name only
			// on
			// a full match. Every other spelling (escapes, whitespace before the colon,
			// proto-name aliases, unknown members) takes the general route below.
			Field field = names.find(reader);
			if (field == null) {
				String name = reader.readFieldName();
				if (name == null) {
					break;
				}
				field = fields.get(name);
				if (field == null) {
					reader.skipValue();
					continue;
				}
			}
			if (reader.nextIfNull()) {
				ProtobufMessageReader.readNullField(builder, field.descriptor());
			} else {
				try {
					field.parser().read(reader, builder, messageReader);
				} catch (RuntimeException | Error e) {
					throw e;
				} catch (Throwable e) {
					throw new IllegalStateException("Cannot set protobuf field " + field.descriptor().getFullName(), e);
				}
			}
		}
	}

	private record Field(FieldDescriptor descriptor, Parser parser, FieldNameMatcher matcher) {
	}

	/**
	 * Open-addressing table from the first four bytes of {@code "jsonName":} (as
	 * read by {@code JSONReader.getRawInt()}) to the fields whose exact-name
	 * matchers share that prefix. Only JSON names that resolve to their own field
	 * through the general name map are entered, so a hit can never disagree with
	 * the map.
	 */
	private static final class NameTable {
		private final int[] keys;
		private final Field[][] candidates;
		private final int shift;
		private final int mask;
		private final boolean empty;

		NameTable(Map<String, Field> byName) {
			Map<Integer, List<Field>> byPrefix = new HashMap<>();
			for (Map.Entry<String, Field> e : byName.entrySet()) {
				Field field = e.getValue();
				if (field.matcher() != null && e.getKey().equals(field.descriptor().getJsonName())) {
					byPrefix.computeIfAbsent(field.matcher().prefix(), k -> new ArrayList<>()).add(field);
				}
			}
			empty = byPrefix.isEmpty();
			int size = Integer.highestOneBit(Math.max(4, byPrefix.size() * 2 - 1)) << 1;
			keys = new int[size];
			candidates = new Field[size][];
			mask = size - 1;
			shift = 32 - Integer.numberOfTrailingZeros(size);
			for (Map.Entry<Integer, List<Field>> e : byPrefix.entrySet()) {
				int i = slot(e.getKey());
				while (keys[i] != 0) {
					i = (i + 1) & mask;
				}
				keys[i] = e.getKey();
				candidates[i] = e.getValue().toArray(new Field[0]);
			}
		}

		private int slot(int prefix) {
			return (prefix * 0x9E3779B1) >>> shift;
		}

		/**
		 * The field whose name is exactly at the reader's position, or null (nothing
		 * consumed).
		 */
		Field find(JSONReader reader) {
			if (empty) {
				return null;
			}
			int raw = reader.getRawInt();
			if (raw == 0) {
				return null;
			}
			int i = slot(raw);
			for (int key = keys[i]; key != 0; key = keys[i]) {
				if (key == raw) {
					Field[] group = candidates[i];
					for (int j = 0; j < group.length; j++) {
						if (group[j].matcher().match(reader)) {
							return group[j];
						}
					}
					return null;
				}
				i = (i + 1) & mask;
			}
			return null;
		}
	}

	@FunctionalInterface
	private interface Parser {
		void read(JSONReader reader, Message.Builder builder, ProtobufMessageReader messageReader) throws Throwable;
	}

	@FunctionalInterface
	private interface ObjectParser {
		Object read(JSONReader reader, ProtobufMessageReader messageReader);
	}

	private static Parser create(FieldDescriptor fd, Class<?> messageClass, Class<?> builderClass)
			throws ReflectiveOperationException {
		String suffix = ProtobufJavaNames.accessorSuffix(fd.getName());
		if (fd.isMapField()) {
			return createMap(fd, suffix, messageClass, builderClass);
		}
		String method = (fd.isRepeated() ? "add" : "set") + suffix;
		Class<?> valueClass = valueClass(fd, suffix, messageClass);
		if (fd.getJavaType() == FieldDescriptor.JavaType.ENUM) {
			method += "Value";
		}
		MethodHandle setter = LOOKUP.unreflect(builderClass.getMethod(method, valueClass)).asType(MethodType
				.methodType(void.class, Message.Builder.class, valueClass.isPrimitive() ? valueClass : Object.class));
		Parser scalar = switch (fd.getJavaType()) {
			case INT -> {
				boolean unsigned = fd.getType() == FieldDescriptor.Type.UINT32
						|| fd.getType() == FieldDescriptor.Type.FIXED32;
				yield (r, b, mr) -> {
					int value = unsigned ? FieldReader.readStrictUint32(r) : FieldReader.readStrictInt32(r);
					setter.invokeExact(b, value);
				};
			}
			case LONG -> {
				boolean unsigned = fd.getType() == FieldDescriptor.Type.UINT64
						|| fd.getType() == FieldDescriptor.Type.FIXED64;
				yield (r, b, mr) -> {
					long value = unsigned ? FieldReader.readUnsignedLong(r) : FieldReader.readSignedLong(r);
					setter.invokeExact(b, value);
				};
			}
			case FLOAT -> (r, b, mr) -> {
				setter.invokeExact(b, FieldReader.readFloatValue(r));
			};
			case DOUBLE -> (r, b, mr) -> {
				setter.invokeExact(b, FieldReader.readDoubleValue(r));
			};
			case BOOLEAN -> (r, b, mr) -> {
				setter.invokeExact(b, r.readBoolValue());
			};
			case ENUM -> {
				Map<String, Integer> names = enumNames(fd.getEnumType());
				yield (r, b, mr) -> {
					setter.invokeExact(b, enumNumber(r, fd, names));
				};
			}
			default -> {
				ObjectParser parser = objectParser(fd, valueClass);
				yield (r, b, mr) -> {
					setter.invokeExact(b, parser.read(r, mr));
				};
			}
		};
		if (!fd.isRepeated()) {
			return scalar;
		}
		// A null list element is skipped, except where null is a value of its own
		// (google.protobuf.Value, google.protobuf.NullValue): there it is NULL_VALUE.
		Parser nullElement = FieldReader.isValue(fd) ? (r, b, mr) -> {
			setter.invokeExact(b, (Object) FieldReader.NULL_VALUE_MESSAGE);
		} : FieldReader.isNullValueEnum(fd) ? (r, b, mr) -> {
			setter.invokeExact(b, 0);
		} : null;
		if (nullElement != null) {
			return (r, b, mr) -> {
				r.nextIfArrayStart();
				while (!r.nextIfArrayEnd()) {
					if (r.nextIfNull()) {
						nullElement.read(r, b, mr);
					} else {
						scalar.read(r, b, mr);
					}
				}
			};
		}
		return (r, b, mr) -> {
			r.nextIfArrayStart();
			while (!r.nextIfArrayEnd()) {
				if (!r.nextIfNull()) {
					scalar.read(r, b, mr);
				}
			}
		};
	}

	private static Parser createMap(FieldDescriptor fd, String suffix, Class<?> messageClass, Class<?> builderClass)
			throws ReflectiveOperationException {
		var keyFd = fd.getMessageType().findFieldByNumber(1);
		var valueFd = fd.getMessageType().findFieldByNumber(2);
		Class<?> keyClass = valueClass(keyFd, "", messageClass);
		boolean enumValue = valueFd.getJavaType() == FieldDescriptor.JavaType.ENUM;
		String stem = suffix + (enumValue ? "Value" : "");
		Class<?> valueClass = messageClass.getMethod("get" + stem + "OrThrow", keyClass).getReturnType();
		MethodHandle setter = LOOKUP.unreflect(builderClass.getMethod("put" + stem, keyClass, valueClass))
				.asType(MethodType.methodType(void.class, Message.Builder.class, Object.class, Object.class));
		ObjectParser parser = objectParser(valueFd, valueClass);
		Object defaultValue = enumValue
				? 0
				: FieldReader.isValue(valueFd)
						? FieldReader.NULL_VALUE_MESSAGE
						: valueFd.getJavaType() == FieldDescriptor.JavaType.MESSAGE
								? ProtobufMessageReader.getDefaultInstance(valueClass)
								: FieldReader.getDefaultMapValue(valueFd);
		return (r, b, mr) -> {
			r.nextIfObjectStart();
			while (!r.nextIfObjectEnd()) {
				String keyText = r.readFieldName();
				if (keyText == null) {
					break;
				}
				Object key = FieldReader.parseMapKey(r, keyText, keyFd);
				Object value = r.nextIfNull() ? defaultValue : parser.read(r, mr);
				setter.invokeExact(b, key, value);
			}
		};
	}

	private static ObjectParser objectParser(FieldDescriptor fd, Class<?> valueClass) {
		if (fd.getJavaType() == FieldDescriptor.JavaType.ENUM) {
			Map<String, Integer> names = enumNames(fd.getEnumType());
			return (r, mr) -> enumNumber(r, fd, names);
		}
		if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
			if (WellKnownTypes.isWellKnownType(fd.getMessageType())) {
				return (r, mr) -> WellKnownTypes.readWkt(r, fd.getMessageType(), mr);
			}
			Message defaultInstance = ProtobufMessageReader.getDefaultInstance(valueClass);
			return (r, mr) -> mr.readMessage(r, fd.getMessageType(), defaultInstance);
		}
		return (r, mr) -> FieldReader.readValue(r, fd, mr);
	}

	/**
	 * Name to number table for one enum type, built when the schema is. It replaces
	 * {@code EnumDescriptor.findValueByName}, which resolves through the file's
	 * symbol table on every call.
	 */
	private static Map<String, Integer> enumNames(EnumDescriptor type) {
		Map<String, Integer> names = new HashMap<>(type.getValues().size() * 2);
		for (EnumValueDescriptor value : type.getValues()) {
			names.put(value.getName(), value.getNumber());
		}
		return names;
	}

	private static int enumNumber(JSONReader reader, FieldDescriptor fd, Map<String, Integer> names) {
		if (reader.isString()) {
			String name = reader.readString();
			Integer number = names.get(name);
			// An unknown name takes the descriptor route, which throws the JSONException.
			return number != null ? number : FieldReader.enumNumber(reader, fd.getEnumType(), name);
		}
		return reader.readInt32Value();
	}

	private static Class<?> valueClass(FieldDescriptor fd, String suffix, Class<?> messageClass)
			throws NoSuchMethodException {
		return switch (fd.getJavaType()) {
			case INT, ENUM -> int.class;
			case LONG -> long.class;
			case FLOAT -> float.class;
			case DOUBLE -> double.class;
			case BOOLEAN -> boolean.class;
			case STRING -> String.class;
			case BYTE_STRING -> ByteString.class;
			case MESSAGE -> (fd.isRepeated()
					? messageClass.getMethod("get" + suffix, int.class)
					: messageClass.getMethod("get" + suffix)).getReturnType();
		};
	}

	private static Parser fallback(FieldDescriptor fd) {
		if (fd.isMapField()) {
			return (r, b, mr) -> FieldReader.readMap(r, b, fd, mr);
		}
		if (fd.isRepeated()) {
			return (r, b, mr) -> FieldReader.readRepeated(r, b, fd, mr);
		}
		return (r, b, mr) -> b.setField(fd, FieldReader.readValue(r, b, fd, mr));
	}
}
