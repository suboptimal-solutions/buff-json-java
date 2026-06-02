package io.suboptimal.buffjson.internal;

import java.util.Base64;

import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONReader;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
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
			case BYTE_STRING -> readBytes(reader);
			case ENUM -> readEnumValue(reader, fd);
			case MESSAGE -> readMessageValue(reader, fd, msgReader);
		};
	}

	/**
	 * Reads a base64 string into a {@link ByteString}, normalizing decode errors to
	 * {@link JSONException}. Public so generated decoders (in other packages) can
	 * call it.
	 */
	public static ByteString readBytes(JSONReader reader) {
		String s = reader.readString();
		try {
			return ByteString.copyFrom(BASE64.decode(s));
		} catch (IllegalArgumentException e) {
			throw new JSONException(reader.info("Invalid base64 value"), e);
		}
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
			String s = reader.readString();
			try {
				return Long.parseLong(s);
			} catch (NumberFormatException e) {
				throw new JSONException(reader.info("Invalid int64 value"), e);
			}
		}
		return reader.readInt64Value();
	}

	/** Reads an unsigned uint64 value that may be a quoted string or number. */
	public static long readUnsignedLong(JSONReader reader) {
		if (reader.isString()) {
			String s = reader.readString();
			try {
				return Long.parseUnsignedLong(s);
			} catch (NumberFormatException e) {
				throw new JSONException(reader.info("Invalid uint64 value"), e);
			}
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
			try {
				return switch (s) {
					case "NaN" -> Float.NaN;
					case "Infinity" -> Float.POSITIVE_INFINITY;
					case "-Infinity" -> Float.NEGATIVE_INFINITY;
					default -> Float.parseFloat(s);
				};
			} catch (NumberFormatException e) {
				throw new JSONException(reader.info("Invalid float value"), e);
			}
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
			try {
				return switch (s) {
					case "NaN" -> Double.NaN;
					case "Infinity" -> Double.POSITIVE_INFINITY;
					case "-Infinity" -> Double.NEGATIVE_INFINITY;
					default -> Double.parseDouble(s);
				};
			} catch (NumberFormatException e) {
				throw new JSONException(reader.info("Invalid double value"), e);
			}
		}
		return reader.readDoubleValue();
	}

	private static EnumValueDescriptor readEnumValue(JSONReader reader, FieldDescriptor fd) {
		if (reader.isString()) {
			return enumValueByName(reader, fd.getEnumType(), reader.readString());
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

	/**
	 * Resolves an enum value name to its descriptor, throwing {@link JSONException}
	 * (not {@code IllegalArgumentException}) on an unknown name. Shared by the
	 * reflection path and {@link #enumNumber}.
	 */
	private static EnumValueDescriptor enumValueByName(JSONReader reader, EnumDescriptor enumType, String name) {
		EnumValueDescriptor evd = enumType.findValueByName(name);
		if (evd == null) {
			throw new JSONException(reader.info("Unknown enum value: " + name + " for " + enumType.getFullName()));
		}
		return evd;
	}

	/**
	 * Resolves an enum value name to its number, throwing {@link JSONException} on
	 * an unknown name. Public so generated decoders (in other packages) can call it
	 * instead of {@code Enum.valueOf}.
	 */
	public static int enumNumber(JSONReader reader, EnumDescriptor enumType, String name) {
		return enumValueByName(reader, enumType, name).getNumber();
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

			Object key = parseMapKey(reader, keyStr, keyFd);

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

	private static Object parseMapKey(JSONReader reader, String keyStr, FieldDescriptor keyFd) {
		return switch (keyFd.getJavaType()) {
			case STRING -> keyStr;
			case INT -> {
				var type = keyFd.getType();
				if (type == FieldDescriptor.Type.UINT32 || type == FieldDescriptor.Type.FIXED32) {
					yield parseUnsignedIntKey(reader, keyStr);
				}
				yield parseIntKey(reader, keyStr);
			}
			case LONG -> {
				var type = keyFd.getType();
				if (type == FieldDescriptor.Type.UINT64 || type == FieldDescriptor.Type.FIXED64) {
					yield parseUnsignedLongKey(reader, keyStr);
				}
				yield parseLongKey(reader, keyStr);
			}
			case BOOLEAN -> parseBoolKey(reader, keyStr);
			// Unreachable for valid descriptors — proto restricts map keys to
			// integral/bool/string. Internal invariant, not untrusted input.
			default -> throw new IllegalArgumentException("Unsupported map key type: " + keyFd.getJavaType());
		};
	}

	// Map-key parse helpers — normalize NumberFormatException to JSONException with
	// the JSON offset. Public so generated decoders (in other packages) can call
	// them.

	/** Parses a signed int32 map key. */
	public static int parseIntKey(JSONReader reader, String keyStr) {
		try {
			return Integer.parseInt(keyStr);
		} catch (NumberFormatException e) {
			throw new JSONException(reader.info("Invalid int32 map key"), e);
		}
	}

	/** Parses an unsigned uint32/fixed32 map key. */
	public static int parseUnsignedIntKey(JSONReader reader, String keyStr) {
		try {
			return (int) Long.parseLong(keyStr);
		} catch (NumberFormatException e) {
			throw new JSONException(reader.info("Invalid uint32 map key"), e);
		}
	}

	/** Parses a signed int64 map key. */
	public static long parseLongKey(JSONReader reader, String keyStr) {
		try {
			return Long.parseLong(keyStr);
		} catch (NumberFormatException e) {
			throw new JSONException(reader.info("Invalid int64 map key"), e);
		}
	}

	/** Parses an unsigned uint64/fixed64 map key. */
	public static long parseUnsignedLongKey(JSONReader reader, String keyStr) {
		try {
			return Long.parseUnsignedLong(keyStr);
		} catch (NumberFormatException e) {
			throw new JSONException(reader.info("Invalid uint64 map key"), e);
		}
	}

	/**
	 * Parses a bool map key. Proto3 JSON only allows the exact strings
	 * {@code "true"}/{@code "false"}; anything else throws {@link JSONException}
	 * rather than silently coercing to {@code false} (which would also collide keys
	 * — e.g. {@code "true"} and {@code "TRUE"} both mapping to {@code true}).
	 */
	public static boolean parseBoolKey(JSONReader reader, String keyStr) {
		if ("true".equals(keyStr)) {
			return true;
		}
		if ("false".equals(keyStr)) {
			return false;
		}
		throw new JSONException(reader.info("Invalid bool map key"));
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
