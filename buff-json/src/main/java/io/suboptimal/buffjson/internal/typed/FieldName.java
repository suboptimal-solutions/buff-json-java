package io.suboptimal.buffjson.internal.typed;

import com.alibaba.fastjson2.JSONWriter;

import io.suboptimal.buffjson.internal.FieldNames;

/**
 * Pre-encoded field name in both UTF-16 ({@code char[]}) and UTF-8
 * ({@code byte[]}) forms, for the runtime write paths (typed accessors and pure
 * reflection). Dispatches on the writer's encoding.
 *
 * <p>
 * <b>Why not the word-packed {@code writeName<n>Raw} path here?</b> Generated
 * encoders use it (see {@link FieldNames}) because they know each name's length
 * at code-generation time and emit the matching call directly. A runtime holder
 * cannot: it has to {@code switch} on the length, and that switch is an
 * indirect branch inside the hottest code, keyed on a per-field instance value.
 * Measured on this repo's benchmarks, that costs more than the
 * {@code arraycopy} it saves — flat on {@code SimpleMessage} (few distinct name
 * lengths) and ~10% down on {@code ComplexMessage} (fifteen fields, nine
 * distinct lengths). So the packed path stays where it is free of dispatch, and
 * the runtime paths keep the pre-encoded arrays.
 */
public record FieldName(char[] chars, byte[] utf8, String escaping) {

	/**
	 * @param jsonName
	 *            the field's JSON name — normally protobuf's lowerCamelCase form,
	 *            but an explicit {@code [json_name = "…"]} can be any string
	 */
	public static FieldName of(String jsonName) {
		if (!FieldNames.isRawWritable(jsonName)) {
			// Non-ASCII or escapable: the pre-encoded arrays cannot represent it, so let
			// fastjson2 escape and transcode it per write.
			return new FieldName(null, null, jsonName);
		}
		return new FieldName(FieldNames.nameWithColonChars(jsonName), FieldNames.nameWithColonBytes(jsonName), null);
	}

	public void writeTo(JSONWriter jw) {
		if (escaping != null) {
			jw.writeName(escaping);
			jw.writeColon();
		} else if (jw.isUTF8()) {
			jw.writeNameRaw(utf8);
		} else {
			jw.writeNameRaw(chars);
		}
	}
}
