package io.suboptimal.buffjson.schema;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import com.google.protobuf.Descriptors.Descriptor;

/**
 * Loads pre-generated JSON Schema text emitted by the protoc plugin, one
 * resource per message at {@code META-INF/buff-json/schema/<fullName>.json}.
 *
 * <p>
 * It is the single home for proto comments:
 * {@link ProtobufSchema#generateJson(Descriptor)} returns this text verbatim,
 * and {@link ProtobufSchema#generate(Descriptor)} overlays its
 * {@code description} fields onto a freshly walked {@code Map} (only the
 * description strings are copied — the map's exact boxed number types
 * {@code Integer}/{@code Long}/{@code Float}/ {@code Double} come from the live
 * walk, since a JSON round-trip could not preserve them). Returns {@code null}
 * when the resource is absent (plugin not run for this message), so callers
 * fall back to the live schema / {@code SourceCodeInfo}.
 */
final class BakedSchema {

	private static final String PREFIX = "META-INF/buff-json/schema/";

	/**
	 * Sentinel for "looked up, not present" — {@link ConcurrentHashMap} forbids
	 * null values.
	 */
	private static final String ABSENT = new String("\0absent\0");

	private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

	private BakedSchema() {
	}

	/**
	 * Returns the baked JSON Schema text for the message, or {@code null} if none
	 * was generated.
	 */
	static String load(Descriptor descriptor) {
		String cached = CACHE.computeIfAbsent(descriptor.getFullName(), BakedSchema::read);
		return cached == ABSENT ? null : cached;
	}

	private static String read(String fullName) {
		String resource = PREFIX + fullName + ".json";
		ClassLoader loader = BakedSchema.class.getClassLoader();
		InputStream in = loader != null
				? loader.getResourceAsStream(resource)
				: ClassLoader.getSystemResourceAsStream(resource);
		if (in == null) {
			return ABSENT;
		}
		try (in) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return ABSENT;
		}
	}
}
