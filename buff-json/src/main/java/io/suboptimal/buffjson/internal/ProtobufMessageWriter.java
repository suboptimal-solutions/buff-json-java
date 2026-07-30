package io.suboptimal.buffjson.internal;

import java.lang.reflect.Type;
import java.util.List;

import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.writer.ObjectWriter;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.TypeRegistry;

import io.suboptimal.buffjson.BuffJsonCodecHolder;
import io.suboptimal.buffjson.BuffJsonGeneratedEncoder;
import io.suboptimal.buffjson.internal.typed.TypedMessageSchema;
/**
 * Core serialization logic for protobuf messages. Implements fastjson2's
 * {@link ObjectWriter} to produce proto3-spec-compliant JSON.
 *
 * <p>
 * Holds settings as instance fields ({@code typeRegistry},
 * {@code useGenerated}, {@code useTyped}) and passes {@code this} through the
 * call chain — no ThreadLocals.
 *
 * <h2>Three-tier dispatch in {@link #writeFields}</h2>
 *
 * For each message, paths are tried in order:
 *
 * <ol>
 * <li><b>Codegen</b> — if {@code useGenerated && message instanceof
 * BuffJsonCodecHolder}, delegates to the generated
 * {@code BuffJsonGeneratedEncoder} (injected via protoc insertion points).
 * Direct typed accessors, no reflection or boxing. Highest throughput.
 * <li><b>Typed-accessor runtime</b> — if {@code useTyped} and the message is
 * not a {@code DynamicMessage}, builds (or reuses) a {@link TypedMessageSchema}
 * that holds {@code LambdaMetafactory}-bound typed lambdas for each field's
 * getter. No {@code getField()} reflection, no boxing. About 80–90% of codegen
 * throughput; activates automatically for any concrete generated message class
 * even without the protoc plugin.
 * <li><b>Pure reflection</b> — looks up the cached {@link MessageSchema},
 * iterates pre-computed {@link MessageSchema.FieldInfo} array (no
 * {@code getAllFields()} TreeMap allocation), pulls each value via
 * {@code message.getField(fd)} (boxes primitives), skips defaults, dispatches
 * to {@link FieldWriter}. Used for {@code DynamicMessage} (e.g., {@code Any}
 * unpacking) and any class where {@code LambdaMetafactory} binding fails — also
 * reachable in tests via {@code setTypedAccessors(false)}.
 * </ol>
 *
 * <p>
 * Field-name writes in tiers 1 and 3 dispatch on {@code jsonWriter.isUTF8()} —
 * UTF-8 writers consume pre-encoded {@code byte[]} (no char→byte transcoding),
 * UTF-16 writers consume {@code char[]}. Tier 2 dispatches inside
 * {@link io.suboptimal.buffjson.internal.typed.FieldName#writeTo}.
 *
 * <p>
 * Default value detection uses raw bit comparison for float/double (to
 * correctly handle {@code -0.0}).
 */
public final class ProtobufMessageWriter implements ObjectWriter<Message> {

	public static final ProtobufMessageWriter INSTANCE = new ProtobufMessageWriter(null, true, true);

	private final TypeRegistry typeRegistry;
	private final boolean useGenerated;
	private final boolean useTyped;

	public ProtobufMessageWriter(TypeRegistry typeRegistry, boolean useGenerated) {
		this(typeRegistry, useGenerated, true);
	}

	public ProtobufMessageWriter(TypeRegistry typeRegistry, boolean useGenerated, boolean useTyped) {
		this.typeRegistry = typeRegistry;
		this.useGenerated = useGenerated;
		this.useTyped = useTyped;
	}

	public TypeRegistry typeRegistry() {
		return typeRegistry;
	}

	@Override
	public void write(JSONWriter jsonWriter, Object object, Object fieldName, Type fieldType, long features) {
		if (object == null) {
			jsonWriter.writeNull();
			return;
		}
		writeMessage(jsonWriter, (Message) object);
	}

	public void writeMessage(JSONWriter jsonWriter, Message message) {
		jsonWriter.startObject();
		writeFields(jsonWriter, message);
		jsonWriter.endObject();
	}

	/**
	 * Writes all non-default fields of a message without the surrounding braces.
	 * Tries the three dispatch tiers in order: codegen (codec-holder), typed
	 * accessors (LambdaMetafactory), pure reflection. See the class Javadoc.
	 */
	@SuppressWarnings("unchecked")
	void writeFields(JSONWriter jsonWriter, Message message) {
		if (useGenerated && message instanceof BuffJsonCodecHolder holder) {
			((BuffJsonGeneratedEncoder<Message>) holder.buffJsonEncoder()).writeFields(jsonWriter, message, this);
			return;
		}

		if (useTyped && !(message instanceof DynamicMessage)) {
			var typed = TypedMessageSchema.forMessage(message.getDescriptorForType(), message.getClass());
			if (typed != null) {
				typed.writeFields(jsonWriter, message, this);
				return;
			}
		}

		var schema = MessageSchema.forDescriptor(message.getDescriptorForType());
		var fields = schema.fields();

		for (var fieldInfo : fields) {
			FieldDescriptor fd = fieldInfo.descriptor();

			if (fieldInfo.isMapField()) {
				List<?> entries = (List<?>) message.getField(fd);
				if (entries.isEmpty())
					continue;
				fieldInfo.name().writeTo(jsonWriter);
				FieldWriter.writeMap(jsonWriter, fieldInfo.mapKeyDescriptor(), fieldInfo.mapValueDescriptor(), entries,
						this);
			} else if (fieldInfo.isRepeated()) {
				List<?> values = (List<?>) message.getField(fd);
				if (values.isEmpty())
					continue;
				fieldInfo.name().writeTo(jsonWriter);
				FieldWriter.writeRepeated(jsonWriter, fd, values, this);
			} else if (fieldInfo.hasPresence()) {
				// hasField first: getField on an absent field boxes a value we would
				// throw away (and materializes a default instance for message fields).
				if (!message.hasField(fd))
					continue;
				fieldInfo.name().writeTo(jsonWriter);
				FieldWriter.writeValue(jsonWriter, fd, message.getField(fd), this);
			} else {
				Object value = message.getField(fd);
				if (isDefaultValue(fieldInfo, value))
					continue;
				fieldInfo.name().writeTo(jsonWriter);
				FieldWriter.writeValue(jsonWriter, fd, value, this);
			}
		}
	}

	private static boolean isDefaultValue(MessageSchema.FieldInfo fieldInfo, Object value) {
		return switch (fieldInfo.javaType()) {
			case INT -> (int) value == 0;
			case LONG -> (long) value == 0L;
			case FLOAT -> Float.floatToRawIntBits((float) value) == 0;
			case DOUBLE -> Double.doubleToRawLongBits((double) value) == 0;
			case BOOLEAN -> !(boolean) value;
			case STRING -> ((String) value).isEmpty();
			case BYTE_STRING -> ((com.google.protobuf.ByteString) value).isEmpty();
			case ENUM -> ((com.google.protobuf.Descriptors.EnumValueDescriptor) value).getNumber() == 0;
			case MESSAGE -> false;
		};
	}
}
