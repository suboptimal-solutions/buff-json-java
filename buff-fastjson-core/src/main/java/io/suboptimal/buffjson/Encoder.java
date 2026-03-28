package io.suboptimal.buffjson;

import com.alibaba.fastjson2.JSON;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TypeRegistry;

/**
 * Configurable encoder for protobuf-to-JSON serialization.
 *
 * <p>
 * Instances are immutable and thread-safe — safe to cache and reuse:
 *
 * <pre>{@code
 * private static final Encoder ENCODER = BuffJSON.encoder().withTypeRegistry(registry);
 *
 * String json = ENCODER.encode(message);
 * }</pre>
 *
 * @see BuffJSON#encoder()
 */
public final class Encoder {

	private final TypeRegistry typeRegistry;

	Encoder(TypeRegistry typeRegistry) {
		this.typeRegistry = typeRegistry;
	}

	/**
	 * Sets the {@link TypeRegistry} for resolving {@code google.protobuf.Any}
	 * fields. Required when the message (or any nested message) contains Any
	 * fields.
	 *
	 * @param registry
	 *            the type registry containing descriptors for types packed in Any
	 * @return a new Encoder with the registry configured
	 */
	public Encoder withTypeRegistry(TypeRegistry registry) {
		return new Encoder(registry);
	}

	/**
	 * Encodes a Protocol Buffer message to its proto3 JSON representation.
	 *
	 * @param message
	 *            the protobuf message or builder to encode
	 * @return compact JSON string (no insignificant whitespace)
	 */
	public String encode(MessageOrBuilder message) {
		Message msg;
		if (message instanceof Message m) {
			msg = m;
		} else {
			msg = ((Message.Builder) message).buildPartial();
		}
		if (typeRegistry != null) {
			BuffJSON.ACTIVE_REGISTRY.set(typeRegistry);
		}
		try {
			return JSON.toJSONString(msg);
		} finally {
			if (typeRegistry != null) {
				BuffJSON.ACTIVE_REGISTRY.remove();
			}
		}
	}
}
