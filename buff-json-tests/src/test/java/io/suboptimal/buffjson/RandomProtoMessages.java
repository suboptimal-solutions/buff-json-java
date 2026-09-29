package io.suboptimal.buffjson;

import java.util.List;
import java.util.Random;
import java.util.Set;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

/**
 * Descriptor-driven random message generator: produces a well-formed instance
 * of <em>any</em> message type, so the differential tests are not tied to one
 * hand-written fixture. Values favour the edges decoders get wrong (limits of
 * every integer type, negative zero, subnormals, escapes and multi-byte text,
 * unknown enum numbers, empty containers).
 */
final class RandomProtoMessages {

	private RandomProtoMessages() {
	}

	/**
	 * Well-known types the generator leaves unset (no fast reader, or needs a type
	 * registry).
	 */
	static final Set<String> UNSUPPORTED_BY_FAST_PATH = Set.of("google.protobuf.Any", "google.protobuf.FieldMask",
			"google.protobuf.Struct", "google.protobuf.Value", "google.protobuf.ListValue");

	@SuppressWarnings("unchecked")
	static <T extends Message> T generate(T defaultInstance, Random rng, int depth, Set<String> skipTypes) {
		Message dynamic = generate(defaultInstance.getDescriptorForType(), rng, depth, skipTypes);
		try {
			return (T) defaultInstance.getParserForType().parseFrom(dynamic.toByteString());
		} catch (InvalidProtocolBufferException e) {
			throw new AssertionError(e);
		}
	}

	static Message generate(Descriptor type, Random rng, int depth, Set<String> skipTypes) {
		DynamicMessage.Builder builder = DynamicMessage.newBuilder(type);
		switch (type.getFullName()) {
			case "google.protobuf.Timestamp" -> {
				builder.setField(type.findFieldByName("seconds"),
						-62_135_596_800L + (long) (rng.nextDouble() * (253_402_300_799L + 62_135_596_800L)));
				builder.setField(type.findFieldByName("nanos"), rng.nextInt(3) == 0 ? 0 : rng.nextInt(1_000_000_000));
				return builder.build();
			}
			case "google.protobuf.Duration" -> {
				long seconds = (long) (rng.nextDouble() * 315_576_000_001L);
				int nanos = rng.nextInt(3) == 0 ? 0 : rng.nextInt(1_000_000_000);
				boolean negative = rng.nextBoolean();
				builder.setField(type.findFieldByName("seconds"), negative ? -seconds : seconds);
				builder.setField(type.findFieldByName("nanos"), negative ? -nanos : nanos);
				return builder.build();
			}
			case "google.protobuf.Value" -> {
				return randomValue(rng, depth);
			}
			case "google.protobuf.Struct" -> {
				return randomStruct(rng, depth);
			}
			case "google.protobuf.ListValue" -> {
				return randomList(rng, depth);
			}
			case "google.protobuf.FieldMask" -> {
				com.google.protobuf.FieldMask.Builder mask = com.google.protobuf.FieldMask.newBuilder();
				for (int i = rng.nextInt(4); i > 0; i--) {
					mask.addPaths("field_" + (char) ('a' + rng.nextInt(10)) + (rng.nextBoolean() ? ".sub_path" : ""));
				}
				return mask.build();
			}
			default -> {
			}
		}
		for (FieldDescriptor fd : type.getFields()) {
			if (fd.getRealContainingOneof() != null && rng.nextInt(3) != 0) {
				continue; // usually leave most oneof members unset
			}
			if (skipTypes.contains(messageTypeName(fd))) {
				continue;
			}
			if (rng.nextInt(4) == 0) {
				continue; // sparse
			}
			if (fd.isMapField()) {
				FieldDescriptor keyFd = fd.getMessageType().findFieldByName("key");
				FieldDescriptor valueFd = fd.getMessageType().findFieldByName("value");
				int n = rng.nextInt(4);
				for (int i = 0; i < n; i++) {
					if (valueFd.getJavaType() == FieldDescriptor.JavaType.MESSAGE && depth <= 0) {
						continue;
					}
					DynamicMessage.Builder entry = DynamicMessage.newBuilder(fd.getMessageType());
					entry.setField(keyFd, value(keyFd, rng, depth, skipTypes));
					entry.setField(valueFd, value(valueFd, rng, depth - 1, skipTypes));
					builder.addRepeatedField(fd, entry.build());
				}
			} else if (fd.isRepeated()) {
				if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE && depth <= 0) {
					continue;
				}
				int n = rng.nextInt(5);
				for (int i = 0; i < n; i++) {
					builder.addRepeatedField(fd, value(fd, rng, depth - 1, skipTypes));
				}
			} else {
				if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE && depth <= 0) {
					continue;
				}
				builder.setField(fd, value(fd, rng, depth - 1, skipTypes));
			}
		}
		return builder.build();
	}

	/**
	 * Full name of the message type a field carries (the value type for maps), or
	 * "".
	 */
	private static String messageTypeName(FieldDescriptor fd) {
		FieldDescriptor carrier = fd.isMapField() ? fd.getMessageType().findFieldByName("value") : fd;
		return carrier.getJavaType() == FieldDescriptor.JavaType.MESSAGE ? carrier.getMessageType().getFullName() : "";
	}

	private static com.google.protobuf.Value randomValue(Random rng, int depth) {
		com.google.protobuf.Value.Builder v = com.google.protobuf.Value.newBuilder();
		int kind = rng.nextInt(depth > 0 ? 6 : 4);
		switch (kind) {
			case 0 -> v.setNullValue(com.google.protobuf.NullValue.NULL_VALUE);
			case 1 -> v.setNumberValue(rng.nextInt(1000) / 8d * (rng.nextBoolean() ? -1 : 1));
			case 2 -> v.setStringValue(randomString(rng));
			case 3 -> v.setBoolValue(rng.nextBoolean());
			case 4 -> v.setStructValue(randomStruct(rng, depth - 1));
			default -> v.setListValue(randomList(rng, depth - 1));
		}
		return v.build();
	}

	private static com.google.protobuf.Struct randomStruct(Random rng, int depth) {
		com.google.protobuf.Struct.Builder s = com.google.protobuf.Struct.newBuilder();
		for (int i = rng.nextInt(4); i > 0; i--) {
			s.putFields("k" + rng.nextInt(20), randomValue(rng, depth - 1));
		}
		return s.build();
	}

	private static com.google.protobuf.ListValue randomList(Random rng, int depth) {
		com.google.protobuf.ListValue.Builder l = com.google.protobuf.ListValue.newBuilder();
		for (int i = rng.nextInt(4); i > 0; i--) {
			l.addValues(randomValue(rng, depth - 1));
		}
		return l.build();
	}

	private static Object value(FieldDescriptor fd, Random rng, int depth, Set<String> skipTypes) {
		return switch (fd.getJavaType()) {
			case INT -> randomInt(rng);
			case LONG -> randomLong(rng);
			case FLOAT -> randomFloat(rng);
			case DOUBLE -> randomDouble(rng);
			case BOOLEAN -> rng.nextBoolean();
			case STRING -> randomString(rng);
			case BYTE_STRING -> {
				byte[] bytes = new byte[rng.nextInt(20)];
				rng.nextBytes(bytes);
				yield ByteString.copyFrom(bytes);
			}
			case ENUM -> randomEnum(fd.getEnumType(), rng);
			case MESSAGE -> generate(fd.getMessageType(), rng, depth, skipTypes);
		};
	}

	static int randomInt(Random rng) {
		int[] edges = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 10, 99, 100, 255, 256, 65535, 65536,
				1_000_000_000, -1_000_000_000};
		return rng.nextInt(4) == 0 ? edges[rng.nextInt(edges.length)] : rng.nextInt() >> rng.nextInt(32);
	}

	static long randomLong(Random rng) {
		long[] edges = {0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, 4294967295L, 4294967296L, 9007199254740993L,
				-9007199254740993L, 1_000_000_000_000_000_000L, 999_999_999_999_999_999L};
		return rng.nextInt(4) == 0 ? edges[rng.nextInt(edges.length)] : rng.nextLong() >> rng.nextInt(64);
	}

	static float randomFloat(Random rng) {
		return switch (rng.nextInt(12)) {
			case 0 -> 0f;
			case 1 -> -0f;
			case 2 -> Float.NaN;
			case 3 -> Float.POSITIVE_INFINITY;
			case 4 -> Float.NEGATIVE_INFINITY;
			case 5 -> Float.MIN_VALUE;
			case 6 -> Float.MAX_VALUE;
			case 7 -> rng.nextInt(100_000) / 100f;
			default -> {
				float f;
				do {
					f = Float.intBitsToFloat(rng.nextInt());
				} while (Float.isNaN(f) || Float.isInfinite(f));
				yield f;
			}
		};
	}

	static double randomDouble(Random rng) {
		return switch (rng.nextInt(12)) {
			case 0 -> 0d;
			case 1 -> -0d;
			case 2 -> Double.NaN;
			case 3 -> Double.POSITIVE_INFINITY;
			case 4 -> Double.NEGATIVE_INFINITY;
			case 5 -> Double.MIN_VALUE;
			case 6 -> Double.MAX_VALUE;
			case 7 -> rng.nextInt(1_000_000) / 1000d;
			default -> {
				double d;
				do {
					d = Double.longBitsToDouble(rng.nextLong());
				} while (Double.isNaN(d) || Double.isInfinite(d));
				yield d;
			}
		};
	}

	static String randomString(Random rng) {
		StringBuilder sb = new StringBuilder();
		int length = rng.nextInt(4) == 0 ? 0 : rng.nextInt(24);
		for (int i = 0; i < length; i++) {
			switch (rng.nextInt(10)) {
				case 0 -> sb.append((char) rng.nextInt(0x20));
				case 1 -> sb.append("\"\\/".charAt(rng.nextInt(3)));
				case 2 -> sb.append((char) (0x80 + rng.nextInt(0x780)));
				case 3 -> {
					char c;
					do {
						c = (char) (0x800 + rng.nextInt(0xF800));
					} while (Character.isSurrogate(c));
					sb.append(c);
				}
				case 4 -> sb.appendCodePoint(0x10000 + rng.nextInt(0x100000));
				default -> sb.append((char) (0x20 + rng.nextInt(0x5f)));
			}
		}
		return sb.toString();
	}

	private static EnumValueDescriptor randomEnum(EnumDescriptor type, Random rng) {
		List<EnumValueDescriptor> values = type.getValues();
		// google.protobuf.NullValue is always written as JSON null, so an unknown
		// number
		// in it cannot be expressed in JSON at all
		if (rng.nextInt(10) == 0 && !type.getFile().toProto().getSyntax().equals("proto2")
				&& !type.getFullName().equals("google.protobuf.NullValue")) {
			return type.findValueByNumberCreatingIfUnknown(1000 + rng.nextInt(1000));
		}
		return values.get(rng.nextInt(values.size()));
	}
}
