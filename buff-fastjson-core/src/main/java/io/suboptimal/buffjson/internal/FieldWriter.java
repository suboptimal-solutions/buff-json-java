package io.suboptimal.buffjson.internal;

import java.util.List;

import com.alibaba.fastjson2.JSONWriter;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.MapEntry;
import com.google.protobuf.Message;

/**
 * Type-dispatched writing of individual protobuf field values to a
 * {@link JSONWriter}.
 *
 * <p>
 * Handles all proto3 JSON formatting rules:
 *
 * <ul>
 * <li><b>uint32/fixed32</b>: unsigned representation via
 * {@link Integer#toUnsignedLong(int)}
 * <li><b>int64 and all 64-bit types</b>: quoted strings (proto3 spec)
 * <li><b>uint64/fixed64</b>: unsigned quoted strings via
 * {@link Long#toUnsignedString(long)}
 * <li><b>float/double</b>: NaN and Infinity as quoted strings (not null)
 * <li><b>bytes</b>: standard Base64 encoding
 * <li><b>enum</b>: string name (handles both {@link EnumValueDescriptor} and
 * raw Integer from map entries)
 * <li><b>message</b>: delegates to {@link WellKnownTypes} or recursive
 * {@link ProtobufMessageWriter}
 * </ul>
 *
 * <p>
 * Also provides {@link #writeRepeated} and {@link #writeMap} for collection
 * fields.
 */
public final class FieldWriter {

	private FieldWriter() {
	}

	/**
	 * Writes a single field value to the JSON writer, dispatching based on the
	 * field's Java type.
	 *
	 * @param jsonWriter
	 *            the fastjson2 writer
	 * @param fd
	 *            the field descriptor (provides type info for format decisions)
	 * @param value
	 *            the field value (boxed for primitives)
	 */
	public static void writeValue(JSONWriter jsonWriter, FieldDescriptor fd, Object value) {
		switch (fd.getJavaType()) {
			case INT -> {
				// Handle unsigned types: uint32/fixed32 need unsigned representation
				var type = fd.getType();
				if (type == FieldDescriptor.Type.UINT32 || type == FieldDescriptor.Type.FIXED32) {
					jsonWriter.writeInt64(Integer.toUnsignedLong((int) value));
				} else {
					jsonWriter.writeInt32((int) value);
				}
			}
			case LONG -> {
				// Proto3 JSON spec: int64/uint64/sint64/sfixed64/fixed64 are quoted
				var type = fd.getType();
				if (type == FieldDescriptor.Type.UINT64 || type == FieldDescriptor.Type.FIXED64) {
					jsonWriter.writeString(Long.toUnsignedString((long) value));
				} else {
					jsonWriter.writeString(Long.toString((long) value));
				}
			}
			case FLOAT -> writeFloatValue(jsonWriter, (float) value);
			case DOUBLE -> writeDoubleValue(jsonWriter, (double) value);
			case BOOLEAN -> jsonWriter.writeBool((boolean) value);
			case STRING -> jsonWriter.writeString((String) value);
			case BYTE_STRING -> {
				ByteString bytes = (ByteString) value;
				jsonWriter.writeString(WellKnownTypes.BASE64.encodeToString(bytes.toByteArray()));
			}
			case ENUM -> {
				if (value instanceof EnumValueDescriptor enumValue) {
					jsonWriter.writeString(enumValue.getName());
				} else {
					// Map values may return Integer for enum fields
					int enumNumber = (int) value;
					var enumDesc = fd.getEnumType().findValueByNumber(enumNumber);
					jsonWriter.writeString(enumDesc != null ? enumDesc.getName() : String.valueOf(enumNumber));
				}
			}
			case MESSAGE -> {
				Message msg = (Message) value;
				if (WellKnownTypes.isWellKnownType(msg.getDescriptorForType())) {
					WellKnownTypes.write(jsonWriter, msg);
				} else {
					ProtobufMessageWriter.INSTANCE.writeMessage(jsonWriter, msg);
				}
			}
		}
	}

	/**
	 * Writes a float value, handling NaN and Infinity as quoted strings per proto3
	 * JSON spec. Also used by {@link WellKnownTypes} for FloatValue wrapper.
	 */
	static void writeFloatValue(JSONWriter jsonWriter, float value) {
		if (Float.isNaN(value)) {
			jsonWriter.writeString("NaN");
		} else if (Float.isInfinite(value)) {
			jsonWriter.writeString(value > 0 ? "Infinity" : "-Infinity");
		} else {
			jsonWriter.writeFloat(value);
		}
	}

	/**
	 * Writes a double value, handling NaN and Infinity as quoted strings per proto3
	 * JSON spec. Also used by {@link WellKnownTypes} for DoubleValue wrapper.
	 */
	static void writeDoubleValue(JSONWriter jsonWriter, double value) {
		if (Double.isNaN(value)) {
			jsonWriter.writeString("NaN");
		} else if (Double.isInfinite(value)) {
			jsonWriter.writeString(value > 0 ? "Infinity" : "-Infinity");
		} else {
			jsonWriter.writeDouble(value);
		}
	}

	/**
	 * Writes a repeated field as a JSON array. Empty arrays should be skipped by
	 * the caller.
	 */
	public static void writeRepeated(JSONWriter jsonWriter, FieldDescriptor fd, List<?> values) {
		jsonWriter.startArray();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0)
				jsonWriter.writeComma();
			writeValue(jsonWriter, fd, values.get(i));
		}
		jsonWriter.endArray();
	}

	/**
	 * Writes a map field as a JSON object. Map keys are always stringified in
	 * proto3 JSON (including numeric and boolean keys). Empty maps should be
	 * skipped by the caller.
	 */
	public static void writeMap(JSONWriter jsonWriter, FieldDescriptor valueDescriptor, List<?> entries) {
		jsonWriter.startObject();
		for (Object entry : entries) {
			MapEntry<?, ?> mapEntry = (MapEntry<?, ?>) entry;
			jsonWriter.writeName(mapEntry.getKey().toString());
			jsonWriter.writeColon();
			writeValue(jsonWriter, valueDescriptor, mapEntry.getValue());
		}
		jsonWriter.endObject();
	}
}
