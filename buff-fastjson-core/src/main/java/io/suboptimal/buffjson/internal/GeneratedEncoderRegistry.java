package io.suboptimal.buffjson.internal;

import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;

import io.suboptimal.buffjson.GeneratedEncoder;

/**
 * Registry of generated per-message-type encoders, discovered via
 * {@link ServiceLoader}.
 *
 * <p>
 * When no generated encoders are on the classpath (the plugin is not used), the
 * registry is empty and {@link #get} always returns {@code null}, causing the
 * generic reflection-based path to be used instead.
 */
public final class GeneratedEncoderRegistry {

	private static final ConcurrentHashMap<String, GeneratedEncoder<?>> ENCODERS_BY_NAME = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Descriptor, GeneratedEncoder<?>> ENCODERS_BY_DESC = new ConcurrentHashMap<>();
	private static final boolean HAS_ENCODERS;

	static {
		ServiceLoader.load(GeneratedEncoder.class).forEach(enc -> ENCODERS_BY_NAME.put(enc.descriptorFullName(), enc));
		HAS_ENCODERS = !ENCODERS_BY_NAME.isEmpty();
	}

	private GeneratedEncoderRegistry() {
	}

	/** Returns {@code true} if any generated encoders are registered. */
	public static boolean hasEncoders() {
		return HAS_ENCODERS;
	}

	/**
	 * Returns the generated encoder for the given message type, or {@code null} if
	 * none is registered. Uses Descriptor identity for fast lookups after the first
	 * call per type.
	 */
	@SuppressWarnings("unchecked")
	public static <T extends Message> GeneratedEncoder<T> get(Descriptor descriptor) {
		var cached = ENCODERS_BY_DESC.get(descriptor);
		if (cached != null) {
			return (GeneratedEncoder<T>) cached;
		}
		var encoder = ENCODERS_BY_NAME.get(descriptor.getFullName());
		if (encoder != null) {
			ENCODERS_BY_DESC.put(descriptor, encoder);
		}
		return (GeneratedEncoder<T>) encoder;
	}
}
