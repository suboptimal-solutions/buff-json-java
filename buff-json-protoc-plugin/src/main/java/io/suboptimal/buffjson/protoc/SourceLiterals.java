package io.suboptimal.buffjson.protoc;

import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONWriter;

/** Literal escaping used by both codec generators. */
final class SourceLiterals {
	private SourceLiterals() {
	}

	/** Pre-encodes a JSON field name, including its quotes and colon. */
	static String jsonFieldName(String name) {
		var context = JSONFactory.createWriteContext();
		context.setFeatures(0);
		try (JSONWriter writer = JSONWriter.of(context)) {
			writer.writeString(name);
			writer.writeColon();
			return writer.toString();
		}
	}

	/**
	 * Quotes a Java string literal. LF and CR must use ordinary escapes: Java
	 * processes Unicode escapes before tokenizing, so Unicode-escaped line breaks
	 * would leave the generated string literal unclosed.
	 */
	static String javaString(String value) {
		StringBuilder sb = new StringBuilder(value.length() + 8).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> {
					if (c >= 0x20 && c <= 0x7e)
						sb.append(c);
					else
						sb.append(String.format("\\u%04x", (int) c));
				}
			}
		}
		return sb.append('"').toString();
	}
}
