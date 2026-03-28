package io.suboptimal.buffjson.internal;

import java.lang.reflect.Type;
import java.util.List;

import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.writer.ObjectWriter;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

/**
 * Core serialization logic for protobuf messages. Implements fastjson2's
 * {@link ObjectWriter} to produce proto3-spec-compliant JSON.
 *
 * <p>
 * For each message:
 *
 * <ol>
 * <li>Looks up the cached {@link MessageSchema} for the message's
 * {@link com.google.protobuf.Descriptors.Descriptor}
 * <li>Iterates the pre-computed {@link MessageSchema.FieldInfo} array (not
 * {@code getAllFields()} — no TreeMap allocation)
 * <li>Skips fields with default values (proto3 semantics) or unset presence
 * fields
 * <li>Delegates value writing to {@link FieldWriter} (scalars, enums, bytes) or
 * recursively to this class (nested messages)
 * </ol>
 *
 * <p>
 * Default value detection uses raw bit comparison for float/double (to
 * correctly handle {@code
 * -0.0}).
 */
public final class ProtobufMessageWriter implements ObjectWriter<Message> {

	public static final ProtobufMessageWriter INSTANCE = new ProtobufMessageWriter();

	private ProtobufMessageWriter() {
	}

	@Override
	public void write(JSONWriter jsonWriter, Object object, Object fieldName, Type fieldType, long features) {
		if (object == null) {
			jsonWriter.writeNull();
			return;
		}
		writeMessage(jsonWriter, (Message) object);
	}

	void writeMessage(JSONWriter jsonWriter, Message message) {
		jsonWriter.startObject();
		writeFields(jsonWriter, message);
		jsonWriter.endObject();
	}

	/**
	 * Writes all non-default fields of a message without the surrounding braces.
	 */
	static void writeFields(JSONWriter jsonWriter, Message message) {
		var schema = MessageSchema.forDescriptor(message.getDescriptorForType());
		var fields = schema.fields();

		for (var fieldInfo : fields) {
			FieldDescriptor fd = fieldInfo.descriptor();

			if (fieldInfo.isMapField()) {
				List<?> entries = (List<?>) message.getField(fd);
				if (entries.isEmpty())
					continue;
				jsonWriter.writeName(fieldInfo.jsonName());
				jsonWriter.writeColon();
				FieldWriter.writeMap(jsonWriter, fieldInfo.mapValueDescriptor(), entries);
			} else if (fieldInfo.isRepeated()) {
				List<?> values = (List<?>) message.getField(fd);
				if (values.isEmpty())
					continue;
				jsonWriter.writeName(fieldInfo.jsonName());
				jsonWriter.writeColon();
				FieldWriter.writeRepeated(jsonWriter, fd, values);
			} else {
				Object value = message.getField(fd);
				if (fieldInfo.hasPresence()) {
					if (!message.hasField(fd))
						continue;
				} else if (isDefaultValue(fieldInfo, value)) {
					continue;
				}
				jsonWriter.writeName(fieldInfo.jsonName());
				jsonWriter.writeColon();
				FieldWriter.writeValue(jsonWriter, fd, value);
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
