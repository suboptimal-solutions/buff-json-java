package io.suboptimal.buffjson.internal.typed;

import java.nio.charset.StandardCharsets;

import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONWriter;

/**
 * Pre-encoded field name in both UTF-16 (char[]) and UTF-8 (byte[]) forms.
 * Dispatches to the optimal variant based on the JSONWriter type.
 */
public record FieldName(char[] chars, byte[] utf8) {

	/**
	 * Escapes once when building a schema, keeping both write paths
	 * allocation-free.
	 */
	public static FieldName of(String jsonName) {
		var context = JSONFactory.createWriteContext();
		// Raw field names have always used double quotes, independent of writer
		// features.
		context.setFeatures(0);
		try (JSONWriter writer = JSONWriter.of(context)) {
			writer.writeString(jsonName);
			writer.writeColon();
			String encoded = writer.toString();
			return new FieldName(encoded.toCharArray(), encoded.getBytes(StandardCharsets.UTF_8));
		}
	}

	public void writeTo(JSONWriter jw) {
		if (jw.isUTF8())
			jw.writeNameRaw(utf8);
		else
			jw.writeNameRaw(chars);
	}
}
