package io.suboptimal.buffjson.internal;

import com.google.protobuf.Descriptors.FieldDescriptor;

/**
 * How protoc names things in the Java classes it generates: the accessor names,
 * and the classes behind message-typed fields.
 */
public final class ProtobufJavaNames {

	private ProtobufJavaNames() {
	}

	public static String accessorSuffix(String name) {
		StringBuilder result = new StringBuilder(name.length());
		boolean capitalize = true;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '_') {
				capitalize = true;
			} else if (c >= '0' && c <= '9') {
				result.append(c);
				capitalize = true;
			} else {
				result.append(capitalize ? Character.toUpperCase(c) : c);
				capitalize = false;
			}
		}
		return result.toString();
	}

	/**
	 * The Java class of the messages a message-typed field of {@code parent} holds,
	 * read from the generated accessors: the return type of the getter of a
	 * singular field, of the indexed getter of a repeated one, and of
	 * {@code getXxxOrThrow} for the value of a map. Reading it from the class (not
	 * deriving it from names) is right whatever protoc did to avoid a name clash.
	 */
	public static Class<?> messageClassOf(Class<?> parent, FieldDescriptor field) throws NoSuchMethodException {
		String suffix = accessorSuffix(field.getName());
		if (field.isMapField()) {
			return parent
					.getMethod("get" + suffix + "OrThrow", mapKeyClass(field.getMessageType().findFieldByNumber(1)))
					.getReturnType();
		}
		return (field.isRepeated() ? parent.getMethod("get" + suffix, int.class) : parent.getMethod("get" + suffix))
				.getReturnType();
	}

	private static Class<?> mapKeyClass(FieldDescriptor key) {
		return switch (key.getJavaType()) {
			case INT -> int.class;
			case LONG -> long.class;
			case BOOLEAN -> boolean.class;
			case STRING -> String.class;
			default -> throw new IllegalArgumentException("Unsupported map key type: " + key.getJavaType());
		};
	}
}
