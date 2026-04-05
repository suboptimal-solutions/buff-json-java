package io.suboptimal.buffjson.schema;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

import io.suboptimal.buffjson.BuffJsonGeneratedComments;

/**
 * Registry of proto source comments discovered via {@link ServiceLoader}.
 *
 * <p>
 * When no generated comment providers are on the classpath (the protoc plugin
 * is not used), the registry is empty and {@link #getComment} always returns
 * {@code null}.
 */
final class GeneratedCommentRegistry {

	private static final Map<String, String> COMMENTS;

	static {
		Map<String, String> merged = new HashMap<>();
		ServiceLoader.load(BuffJsonGeneratedComments.class).forEach(p -> merged.putAll(p.getComments()));
		COMMENTS = merged;
	}

	private GeneratedCommentRegistry() {
	}

	/**
	 * Returns the proto source comment for the given full name, or {@code null}.
	 */
	static String getComment(String fullName) {
		return COMMENTS.get(fullName);
	}
}
