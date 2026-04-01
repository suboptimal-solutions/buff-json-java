package io.suboptimal.buffjson;

import com.alibaba.fastjson2.JSON;
import com.google.protobuf.Message;
import com.google.protobuf.TypeRegistry;

/**
 * Configurable decoder for JSON-to-protobuf deserialization.
 *
 * <p>
 * Instances are immutable and thread-safe — safe to cache and reuse:
 *
 * <pre>{@code
 * private static final Decoder DECODER = BuffJSON.decoder().withTypeRegistry(registry);
 *
 * MyMessage msg = DECODER.decode(json, MyMessage.class);
 * }</pre>
 *
 * @see BuffJSON#decoder()
 */
public final class Decoder {

	private final TypeRegistry typeRegistry;
	private final boolean useGeneratedDecoders;

	Decoder(TypeRegistry typeRegistry) {
		this(typeRegistry, true);
	}

	private Decoder(TypeRegistry typeRegistry, boolean useGeneratedDecoders) {
		this.typeRegistry = typeRegistry;
		this.useGeneratedDecoders = useGeneratedDecoders;
	}

	/**
	 * Sets the {@link TypeRegistry} for resolving {@code google.protobuf.Any}
	 * fields. Required when the JSON (or any nested content) contains Any fields.
	 *
	 * @param registry
	 *            the type registry containing descriptors for types packed in Any
	 * @return a new Decoder with the registry configured
	 */
	public Decoder withTypeRegistry(TypeRegistry registry) {
		return new Decoder(registry, useGeneratedDecoders);
	}

	/**
	 * Controls whether generated decoders (from
	 * {@code buff-fastjson-protoc-plugin}) are used when available. Defaults to
	 * {@code true}.
	 *
	 * <p>
	 * Setting to {@code false} forces the generic reflection-based path, useful for
	 * benchmarking or testing both paths independently.
	 *
	 * @return a new Decoder with the setting applied
	 */
	public Decoder withGeneratedDecoders(boolean enabled) {
		return new Decoder(typeRegistry, enabled);
	}

	/**
	 * Decodes a proto3 JSON string to a Protocol Buffer message.
	 *
	 * @param json
	 *            the JSON string to decode
	 * @param messageClass
	 *            the target protobuf message class
	 * @return the decoded protobuf message
	 */
	public <T extends Message> T decode(String json, Class<T> messageClass) {
		if (typeRegistry != null) {
			BuffJSON.ACTIVE_REGISTRY.set(typeRegistry);
		}
		if (!useGeneratedDecoders) {
			BuffJSON.SKIP_GENERATED_DECODERS.set(Boolean.TRUE);
		}
		try {
			return JSON.parseObject(json, messageClass);
		} finally {
			if (typeRegistry != null) {
				BuffJSON.ACTIVE_REGISTRY.remove();
			}
			if (!useGeneratedDecoders) {
				BuffJSON.SKIP_GENERATED_DECODERS.remove();
			}
		}
	}
}
