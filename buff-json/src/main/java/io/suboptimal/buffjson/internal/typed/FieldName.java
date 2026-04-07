package io.suboptimal.buffjson.internal.typed;

import com.alibaba.fastjson2.JSONWriter;

/**
 * Pre-encoded field name in both UTF-16 (char[]) and UTF-8 (byte[]) forms.
 * Dispatches to the optimal variant based on the JSONWriter type.
 */
public record FieldName(char[] chars, byte[] utf8) {

	public void writeTo(JSONWriter jw) {
		if (jw.isUTF8())
			jw.writeNameRaw(utf8);
		else
			jw.writeNameRaw(chars);
	}
}
