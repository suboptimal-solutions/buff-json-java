package io.suboptimal.buffjson.protoc;

import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONWriter;

import io.suboptimal.buffjson.internal.codegen.SourcePrinter;

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

	/** Quotes a Java string literal. */
	static String javaString(String value) {
		return SourcePrinter.javaString(value);
	}
}
