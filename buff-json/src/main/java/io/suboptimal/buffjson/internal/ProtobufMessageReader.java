package io.suboptimal.buffjson.internal;

import java.lang.reflect.Type;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.reader.ObjectReader;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.TypeRegistry;

import io.suboptimal.buffjson.BuffJsonCodecHolder;
import io.suboptimal.buffjson.BuffJsonGeneratedDecoder;
/**
 * Core deserialization logic for protobuf messages. Implements fastjson2's
 * {@link ObjectReader} to parse proto3-spec-compliant JSON.
 *
 * <p>
 * Holds settings as instance fields ({@code typeRegistry},
 * {@code useGenerated}) and passes {@code this} through the call chain — no
 * ThreadLocals.
 *
 * <p>
 * For each message:
 *
 * <ol>
 * <li>Resolves the concrete Message class and gets its default instance
 * <li>Checks {@code defaultInstance instanceof BuffJsonCodecHolder} for a
 * generated decoder (injected via protoc insertion points)
 * <li>Caches the decoder by Descriptor for the descriptor-only nested decode
 * path ({@link GeneratedDecoderRegistry})
 * <li>Falls back to reflection-based field-by-field parsing using
 * {@link MessageSchema} and {@link FieldReader}
 * </ol>
 */
public final class ProtobufMessageReader implements ObjectReader<Message> {

	public static final ProtobufMessageReader INSTANCE = new ProtobufMessageReader(null, true);

	private static final ConcurrentHashMap<Class<?>, Message> DEFAULT_INSTANCE_CACHE = new ConcurrentHashMap<>();

	private final TypeRegistry typeRegistry;
	private final boolean useGenerated;

	public ProtobufMessageReader(TypeRegistry typeRegistry, boolean useGenerated) {
		this.typeRegistry = typeRegistry;
		this.useGenerated = useGenerated;
	}

	public TypeRegistry typeRegistry() {
		return typeRegistry;
	}

	@Override
	public Message readObject(JSONReader reader, Type fieldType, Object fieldName, long features) {
		if (reader.nextIfNull()) {
			return null;
		}

		Class<?> clazz;
		if (fieldType instanceof Class<?> c) {
			clazz = c;
		} else {
			throw new IllegalArgumentException("Cannot resolve Message class from type: " + fieldType);
		}

		Message defaultInstance = getDefaultInstance(clazz);
		Descriptor descriptor = defaultInstance.getDescriptorForType();

		return readMessage(reader, descriptor, defaultInstance);
	}

	/**
	 * Reads a message using the generated path if available via the descriptor
	 * cache, otherwise the runtime reflection-based path. Falls back to
	 * DynamicMessage for the runtime path. Used for nested messages where the
	 * concrete class is not known.
	 */
	@SuppressWarnings("unchecked")
	public Message readMessage(JSONReader reader, Descriptor descriptor) {
		if (useGenerated) {
			BuffJsonGeneratedDecoder<Message> decoder = GeneratedDecoderRegistry.get(descriptor);
			if (decoder != null) {
				return decoder.readMessage(reader, this);
			}
		}

		Message defaultInstance = DynamicMessage.getDefaultInstance(descriptor);
		return readMessageRuntime(reader, descriptor, defaultInstance);
	}

	/**
	 * Reads a message using the generated path if available, otherwise the runtime
	 * reflection-based path with the provided typed default instance. Used for
	 * top-level decoding where the concrete Message class is known.
	 */
	@SuppressWarnings("unchecked")
	public Message readMessage(JSONReader reader, Descriptor descriptor, Message defaultInstance) {
		if (useGenerated && defaultInstance instanceof BuffJsonCodecHolder holder) {
			@SuppressWarnings("unchecked")
			BuffJsonGeneratedDecoder<Message> decoder = (BuffJsonGeneratedDecoder<Message>) holder.buffJsonDecoder();
			if (GeneratedDecoderRegistry.get(descriptor) == null) {
				GeneratedDecoderRegistry.put(descriptor, decoder);
			}
			return decoder.readMessage(reader, this);
		}

		return readMessageRuntime(reader, descriptor, defaultInstance);
	}

	/**
	 * Runtime reflection-based message reading. Always uses the descriptor/builder
	 * path.
	 */
	Message readMessageRuntime(JSONReader reader, Descriptor descriptor, Message defaultInstance) {
		Message.Builder builder = defaultInstance.newBuilderForType();
		reader.nextIfObjectStart();
		readFieldsInto(reader, builder, descriptor);
		return builder.build();
	}

	/**
	 * Reads a {@code DynamicMessage} of {@code descriptor} from the reader's
	 * <em>current</em> position — the object-start (and possibly some leading
	 * fields) have already been consumed by the caller. Used by the Any
	 * {@code @type}-first fast path to decode content fields directly off the live
	 * reader without buffering and re-parsing.
	 */
	public Message readRemainingMessageFields(JSONReader reader, Descriptor descriptor) {
		Message.Builder builder = DynamicMessage.newBuilder(descriptor);
		readFieldsInto(reader, builder, descriptor);
		return builder.build();
	}

	/**
	 * Reads JSON object fields into {@code builder} until object-end. Assumes the
	 * reader is positioned just after the opening brace (at the first field name or
	 * the closing brace).
	 */
	private void readFieldsInto(JSONReader reader, Message.Builder builder, Descriptor descriptor) {
		MessageSchema schema = MessageSchema.forDescriptor(descriptor);

		while (!reader.nextIfObjectEnd()) {
			String fieldName = reader.readFieldName();
			if (fieldName == null) {
				break;
			}

			MessageSchema.FieldInfo fieldInfo = schema.fieldByJsonName(fieldName);
			if (fieldInfo == null) {
				reader.skipValue();
				continue;
			}

			FieldDescriptor fd = fieldInfo.descriptor();

			if (reader.nextIfNull()) {
				// For google.protobuf.Value, null means NullValue, not "absent"
				if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE
						&& "google.protobuf.Value".equals(fd.getMessageType().getFullName())) {
					builder.setField(fd, com.google.protobuf.Value.newBuilder()
							.setNullValue(com.google.protobuf.NullValue.NULL_VALUE).build());
				}
				continue;
			}
			if (fieldInfo.isMapField()) {
				FieldReader.readMap(reader, builder, fd, this);
			} else if (fieldInfo.isRepeated()) {
				FieldReader.readRepeated(reader, builder, fd, this);
			} else {
				Object value = FieldReader.readValue(reader, fd, this);
				builder.setField(fd, value);
			}
		}
	}

	@SuppressWarnings("unchecked")
	public static <T extends Message> T getDefaultInstance(Class<?> clazz) {
		return (T) DEFAULT_INSTANCE_CACHE.computeIfAbsent(clazz, c -> {
			try {
				return (Message) c.getMethod("getDefaultInstance").invoke(null);
			} catch (Exception e) {
				throw new IllegalArgumentException("Cannot get default instance for " + c.getName(), e);
			}
		});
	}
}
