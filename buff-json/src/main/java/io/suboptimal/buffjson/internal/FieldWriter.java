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
 * <li><b>int64 (signed)</b>: quoted strings via {@code writeString(long)} —
 * writes directly to buffer, no {@code Long.toString()} allocation
 * <li><b>uint64/fixed64</b>: unsigned quoted strings via
 * {@link WellKnownTypes#writeUnsignedLongString} — no String allocation
 * <li><b>float/double</b>: NaN and Infinity as quoted strings (not null)
 * <li><b>bytes</b>: {@code writeBase64(byte[])} — fastjson2 encodes Base64
 * directly into the output buffer, no intermediate String
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
	 * @param writer
	 *            the message writer for recursive nested message writes
	 */
	public static void writeValue(JSONWriter jsonWriter, FieldDescriptor fd, Object value,
			ProtobufMessageWriter writer) {
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
					WellKnownTypes.writeUnsignedLongString(jsonWriter, (long) value);
				} else {
					// writeString(long) writes the quoted number directly — no String allocation
					jsonWriter.writeString((long) value);
				}
			}
			case FLOAT -> writeFloatValue(jsonWriter, (float) value);
			case DOUBLE -> writeDoubleValue(jsonWriter, (double) value);
			case BOOLEAN -> jsonWriter.writeBool((boolean) value);
			case STRING -> jsonWriter.writeString((String) value);
			case BYTE_STRING -> {
				ByteString bytes = (ByteString) value;
				jsonWriter.writeBase64(bytes.toByteArray());
			}
			case ENUM -> {
				if (value instanceof EnumValueDescriptor enumValue) {
					// Unrecognized values are written as integers per proto3 JSON spec.
					// Detect them by checking if the number maps to a known value.
					if (fd.getEnumType().findValueByNumber(enumValue.getNumber()) != null) {
						jsonWriter.writeString(enumValue.getName());
					} else {
						jsonWriter.writeInt32(enumValue.getNumber());
					}
				} else {
					// Map values may return Integer for enum fields.
					int enumNumber = (int) value;
					var enumDesc = fd.getEnumType().findValueByNumber(enumNumber);
					if (enumDesc != null) {
						jsonWriter.writeString(enumDesc.getName());
					} else {
						jsonWriter.writeInt32(enumNumber);
					}
				}
			}
			case MESSAGE -> {
				Message msg = (Message) value;
				if (WellKnownTypes.isWellKnownType(msg.getDescriptorForType())) {
					WellKnownTypes.write(jsonWriter, msg, writer);
				} else {
					writer.writeMessage(jsonWriter, msg);
				}
			}
		}
	}

	/**
	 * Writes a float value, handling NaN and Infinity as quoted strings per proto3
	 * JSON spec. Also used by {@link WellKnownTypes} for FloatValue wrapper.
	 */
	static void writeFloatValue(JSONWriter jsonWriter, float value) {
		if (Float.isFinite(value)) {
			jsonWriter.writeFloat(value);
		} else if (Float.isNaN(value)) {
			jsonWriter.writeString("NaN");
		} else {
			jsonWriter.writeString(value > 0 ? "Infinity" : "-Infinity");
		}
	}

	/**
	 * Writes a double value, handling NaN and Infinity as quoted strings per proto3
	 * JSON spec. Also used by {@link WellKnownTypes} for DoubleValue wrapper.
	 */
	static void writeDoubleValue(JSONWriter jsonWriter, double value) {
		if (Double.isFinite(value)) {
			jsonWriter.writeDouble(value);
		} else if (Double.isNaN(value)) {
			jsonWriter.writeString("NaN");
		} else {
			jsonWriter.writeString(value > 0 ? "Infinity" : "-Infinity");
		}
	}

	/**
	 * Writes a repeated field as a JSON array. Empty arrays should be skipped by
	 * the caller.
	 */
	public static void writeRepeated(JSONWriter jsonWriter, FieldDescriptor fd, List<?> values,
			ProtobufMessageWriter writer) {
		jsonWriter.startArray();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0)
				jsonWriter.writeComma();
			writeValue(jsonWriter, fd, values.get(i), writer);
		}
		jsonWriter.endArray();
	}

	/**
	 * Writes a map field as a JSON object. Map keys are always stringified in
	 * proto3 JSON (including numeric and boolean keys). Empty maps should be
	 * skipped by the caller.
	 */
	public static void writeMap(JSONWriter jsonWriter, FieldDescriptor valueDescriptor, List<?> entries,
			ProtobufMessageWriter writer) {
		jsonWriter.startObject();
		for (Object entry : entries) {
			MapEntry<?, ?> mapEntry = (MapEntry<?, ?>) entry;
			jsonWriter.writeName(mapEntry.getKey().toString());
			jsonWriter.writeColon();
			writeValue(jsonWriter, valueDescriptor, mapEntry.getValue(), writer);
		}
		jsonWriter.endObject();
	}
}
