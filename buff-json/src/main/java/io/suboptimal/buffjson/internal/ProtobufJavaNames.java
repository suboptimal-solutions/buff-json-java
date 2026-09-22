package io.suboptimal.buffjson.internal;

/**
 * Protoc's underscore/digit-to-camel-case rule for public Java accessor names.
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
}
