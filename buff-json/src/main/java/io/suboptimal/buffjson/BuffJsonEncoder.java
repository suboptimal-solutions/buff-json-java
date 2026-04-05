package io.suboptimal.buffjson;

import java.io.IOException;
import java.io.OutputStream;

import com.alibaba.fastjson2.JSON;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TypeRegistry;

/**
 * Configurable encoder for protobuf-to-JSON serialization.
 *
 * <pre>{@code
 * BuffJsonEncoder encoder = BuffJson.encoder().setTypeRegistry(registry);
 *
 * String json = encoder.encode(message);
 * byte[] bytes = encoder.encodeToBytes(message);
 * encoder.encode(message, outputStream);
 * }</pre>
 *
 * @see BuffJson#encoder()
 */
public final class BuffJsonEncoder {

	private TypeRegistry typeRegistry;
	private boolean useGeneratedEncoders = true;

	BuffJsonEncoder() {
	}

	public BuffJsonEncoder setTypeRegistry(TypeRegistry registry) {
		this.typeRegistry = registry;
		return this;
	}

	public TypeRegistry getTypeRegistry() {
		return typeRegistry;
	}

	public BuffJsonEncoder setGeneratedEncoders(boolean enabled) {
		this.useGeneratedEncoders = enabled;
		return this;
	}

	public boolean getGeneratedEncoders() {
		return useGeneratedEncoders;
	}

	/**
	 * Encodes a Protocol Buffer message to its proto3 JSON string.
	 */
	public String encode(MessageOrBuilder message) {
		Message msg = toMessage(message);
		setupThreadLocals();
		try {
			return JSON.toJSONString(msg);
		} finally {
			clearThreadLocals();
		}
	}

	/**
	 * Encodes a Protocol Buffer message to a UTF-8 JSON byte array.
	 */
	public byte[] encodeToBytes(MessageOrBuilder message) {
		Message msg = toMessage(message);
		setupThreadLocals();
		try {
			return JSON.toJSONBytes(msg);
		} finally {
			clearThreadLocals();
		}
	}

	/**
	 * Encodes a Protocol Buffer message and writes the JSON directly to an
	 * {@link OutputStream}.
	 */
	public void encode(MessageOrBuilder message, OutputStream out) throws IOException {
		Message msg = toMessage(message);
		setupThreadLocals();
		try {
			JSON.writeTo(out, msg);
		} finally {
			clearThreadLocals();
		}
	}

	private static Message toMessage(MessageOrBuilder message) {
		if (message instanceof Message m) {
			return m;
		}
		return ((Message.Builder) message).buildPartial();
	}

	private void setupThreadLocals() {
		if (typeRegistry != null) {
			BuffJson.ACTIVE_REGISTRY.set(typeRegistry);
		}
		if (!useGeneratedEncoders) {
			BuffJson.SKIP_GENERATED.set(Boolean.TRUE);
		}
	}

	private void clearThreadLocals() {
		if (typeRegistry != null) {
			BuffJson.ACTIVE_REGISTRY.remove();
		}
		if (!useGeneratedEncoders) {
			BuffJson.SKIP_GENERATED.remove();
		}
	}
}
