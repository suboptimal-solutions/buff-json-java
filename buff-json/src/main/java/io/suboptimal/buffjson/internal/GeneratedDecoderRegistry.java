package io.suboptimal.buffjson.internal;

import java.util.concurrent.ConcurrentHashMap;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;

import io.suboptimal.buffjson.BuffJsonGeneratedDecoder;

/**
 * Cache of generated per-message-type decoders, populated as a side-effect of
 * {@code instanceof BuffJsonCodecHolder} discovery in
 * {@link ProtobufMessageReader}.
 *
 * <p>
 * This cache exists solely for the descriptor-only decode path (nested messages
 * in the runtime reflection path). The primary lookup uses
 * {@code instanceof BuffJsonCodecHolder} directly on the message's default
 * instance.
 */
public final class GeneratedDecoderRegistry {

	private static final ConcurrentHashMap<Descriptor, BuffJsonGeneratedDecoder<?>> DECODERS_BY_DESC = new ConcurrentHashMap<>();

	private GeneratedDecoderRegistry() {
	}

	/**
	 * Caches a decoder for the given descriptor. Called when a decoder is
	 * discovered via {@code instanceof BuffJsonCodecHolder}.
	 */
	public static void put(Descriptor descriptor, BuffJsonGeneratedDecoder<?> decoder) {
		DECODERS_BY_DESC.putIfAbsent(descriptor, decoder);
	}

	/**
	 * Returns the cached decoder for the given descriptor, or {@code null} if none
	 * has been cached yet.
	 */
	@SuppressWarnings("unchecked")
	public static <T extends Message> BuffJsonGeneratedDecoder<T> get(Descriptor descriptor) {
		return (BuffJsonGeneratedDecoder<T>) DECODERS_BY_DESC.get(descriptor);
	}
}
