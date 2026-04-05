package io.suboptimal.buffjson.internal;

import java.util.Base64;

import com.alibaba.fastjson2.JSONReader;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;

/**
 * Type-dispatched reading of individual protobuf field values from a
 * {@link JSONReader}.
 *
 * <p>
 * Handles all proto3 JSON parsing rules:
 *
 * <ul>
 * <li><b>uint32/fixed32</b>: unsigned range via casting from long
 * <li><b>int64 and all 64-bit types</b>: accept both quoted strings and numbers
 * (proto3 spec)
 * <li><b>uint64/fixed64</b>: unsigned parsing via
 * {@link Long#parseUnsignedLong(String)}
 * <li><b>float/double</b>: NaN and Infinity as quoted strings
 * <li><b>bytes</b>: standard Base64 decoding
 * <li><b>enum</b>: string name or integer value
 * <li><b>message</b>: delegates to {@link WellKnownTypes} or recursive
 * {@link ProtobufMessageReader}
 * </ul>
 */
public final class FieldReader {

	public static final Base64.Decoder BASE64 = Base64.getDecoder();

	private FieldReader() {
	}

	/**
	 * Reads a single field value from the JSON reader, dispatching based on the
	 * field's Java type.
	 */
	public static Object readValue(JSONReader reader, FieldDescriptor fd, ProtobufMessageReader msgReader) {
		return switch (fd.getJavaType()) {
			case INT -> readIntValue(reader, fd);
			case LONG -> readLongValue(reader, fd);
			case FLOAT -> readFloatValue(reader);
			case DOUBLE -> readDoubleValue(reader);
			case BOOLEAN -> reader.readBoolValue();
			case STRING -> reader.readString();
			case BYTE_STRING -> ByteString.copyFrom(BASE64.decode(reader.readString()));
			case ENUM -> readEnumValue(reader, fd);
			case MESSAGE -> readMessageValue(reader, fd, msgReader);
		};
	}

	private static int readIntValue(JSONReader reader, FieldDescriptor fd) {
		var type = fd.getType();
		if (type == FieldDescriptor.Type.UINT32 || type == FieldDescriptor.Type.FIXED32) {
			// Unsigned: can be up to 4294967295, exceeds signed int range
			return (int) reader.readInt64Value();
		}
		return reader.readInt32Value();
	}

	private static long readLongValue(JSONReader reader, FieldDescriptor fd) {
		var type = fd.getType();
		if (type == FieldDescriptor.Type.UINT64 || type == FieldDescriptor.Type.FIXED64) {
			return readUnsignedLong(reader);
		}
		return readSignedLong(reader);
	}

	/** Reads a signed int64 value that may be a quoted string or number. */
	public static long readSignedLong(JSONReader reader) {
		if (reader.isString()) {
			return Long.parseLong(reader.readString());
		}
		return reader.readInt64Value();
	}

	/** Reads an unsigned uint64 value that may be a quoted string or number. */
	public static long readUnsignedLong(JSONReader reader) {
		if (reader.isString()) {
			return Long.parseUnsignedLong(reader.readString());
		}
		return reader.readInt64Value();
	}

	/**
	 * Reads a float value, handling "NaN", "Infinity", "-Infinity" as quoted
	 * strings.
	 */
	public static float readFloatValue(JSONReader reader) {
		if (reader.isString()) {
			String s = reader.readString();
			return switch (s) {
				case "NaN" -> Float.NaN;
				case "Infinity" -> Float.POSITIVE_INFINITY;
				case "-Infinity" -> Float.NEGATIVE_INFINITY;
				default -> Float.parseFloat(s);
			};
		}
		return reader.readFloatValue();
	}

	/**
	 * Reads a double value, handling "NaN", "Infinity", "-Infinity" as quoted
	 * strings.
	 */
	public static double readDoubleValue(JSONReader reader) {
		if (reader.isString()) {
			String s = reader.readString();
			return switch (s) {
				case "NaN" -> Double.NaN;
				case "Infinity" -> Double.POSITIVE_INFINITY;
				case "-Infinity" -> Double.NEGATIVE_INFINITY;
				default -> Double.parseDouble(s);
			};
		}
		return reader.readDoubleValue();
	}

	private static EnumValueDescriptor readEnumValue(JSONReader reader, FieldDescriptor fd) {
		if (reader.isString()) {
			String name = reader.readString();
			EnumValueDescriptor evd = fd.getEnumType().findValueByName(name);
			if (evd == null) {
				throw new IllegalArgumentException(
						"Unknown enum value: " + name + " for " + fd.getEnumType().getFullName());
			}
			return evd;
		}
		int number = reader.readInt32Value();
		EnumValueDescriptor evd = fd.getEnumType().findValueByNumber(number);
		if (evd != null) {
			return evd;
		}
		// Unrecognized enum number — return the default value (proto3 behavior)
		// The generic path using setField() can't represent unrecognized enums
		return fd.getEnumType().findValueByNumber(0);
	}

	private static Message readMessageValue(JSONReader reader, FieldDescriptor fd, ProtobufMessageReader msgReader) {
		Descriptor msgDesc = fd.getMessageType();
		if (WellKnownTypes.isWellKnownType(msgDesc)) {
			return WellKnownTypes.readWkt(reader, msgDesc, msgReader);
		}
		return msgReader.readMessage(reader, msgDesc);
	}

	/**
	 * Reads a repeated field as a JSON array, adding each element to the builder.
	 */
	public static void readRepeated(JSONReader reader, Message.Builder builder, FieldDescriptor fd,
			ProtobufMessageReader msgReader) {
		reader.nextIfArrayStart();
		while (!reader.nextIfArrayEnd()) {
			if (reader.nextIfNull()) {
				continue;
			}
			Object value = readValue(reader, fd, msgReader);
			builder.addRepeatedField(fd, value);
		}
	}

	/**
	 * Reads a map field as a JSON object, adding entries to the builder.
	 */
	public static void readMap(JSONReader reader, Message.Builder builder, FieldDescriptor fd,
			ProtobufMessageReader msgReader) {
		Descriptor entryDesc = fd.getMessageType();
		FieldDescriptor keyFd = entryDesc.findFieldByName("key");
		FieldDescriptor valueFd = entryDesc.findFieldByName("value");

		reader.nextIfObjectStart();
		while (!reader.nextIfObjectEnd()) {
			String keyStr = reader.readFieldName();
			if (keyStr == null) {
				break;
			}

			Object key = parseMapKey(keyStr, keyFd);

			Object value;
			if (reader.nextIfNull()) {
				value = getDefaultMapValue(valueFd);
			} else {
				value = readValue(reader, valueFd, msgReader);
			}

			Message.Builder entryBuilder = builder.newBuilderForField(fd);
			entryBuilder.setField(keyFd, key);
			entryBuilder.setField(valueFd, value);
			builder.addRepeatedField(fd, entryBuilder.build());
		}
	}

	private static Object parseMapKey(String keyStr, FieldDescriptor keyFd) {
		return switch (keyFd.getJavaType()) {
			case STRING -> keyStr;
			case INT -> {
				var type = keyFd.getType();
				if (type == FieldDescriptor.Type.UINT32 || type == FieldDescriptor.Type.FIXED32) {
					yield (int) Long.parseLong(keyStr);
				}
				yield Integer.parseInt(keyStr);
			}
			case LONG -> {
				var type = keyFd.getType();
				if (type == FieldDescriptor.Type.UINT64 || type == FieldDescriptor.Type.FIXED64) {
					yield Long.parseUnsignedLong(keyStr);
				}
				yield Long.parseLong(keyStr);
			}
			case BOOLEAN -> Boolean.parseBoolean(keyStr);
			default -> throw new IllegalArgumentException("Unsupported map key type: " + keyFd.getJavaType());
		};
	}

	private static Object getDefaultMapValue(FieldDescriptor valueFd) {
		return switch (valueFd.getJavaType()) {
			case INT -> 0;
			case LONG -> 0L;
			case FLOAT -> 0.0f;
			case DOUBLE -> 0.0;
			case BOOLEAN -> false;
			case STRING -> "";
			case BYTE_STRING -> ByteString.EMPTY;
			case ENUM -> valueFd.getEnumType().findValueByNumber(0);
			case MESSAGE -> DynamicMessage.getDefaultInstance(valueFd.getMessageType());
		};
	}
}
