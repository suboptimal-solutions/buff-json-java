package io.suboptimal.buffjson.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.fastjson2.JSONReader;
import com.google.protobuf.ByteString;
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
			Field field = new Field(fd, parser);
			fields.put(fd.getJsonName(), field);
			fields.put(fd.getName(), field);
		}
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
			String name = reader.readFieldName();
			if (name == null) {
				break;
			}
			Field field = fields.get(name);
			if (field == null) {
				reader.skipValue();
			} else if (reader.nextIfNull()) {
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

	private record Field(FieldDescriptor descriptor, Parser parser) {
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
			case ENUM -> (r, b, mr) -> {
				setter.invokeExact(b, enumNumber(r, fd));
			};
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
			return (r, mr) -> enumNumber(r, fd);
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

	private static int enumNumber(JSONReader reader, FieldDescriptor fd) {
		return reader.isString()
				? FieldReader.enumNumber(reader, fd.getEnumType(), reader.readString())
				: reader.readInt32Value();
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
