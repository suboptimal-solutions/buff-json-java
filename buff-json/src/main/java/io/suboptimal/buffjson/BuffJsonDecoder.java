package io.suboptimal.buffjson;

import java.io.InputStream;

import com.alibaba.fastjson2.JSON;
import com.google.protobuf.Message;
import com.google.protobuf.TypeRegistry;

/**
 * Configurable decoder for JSON-to-protobuf deserialization.
 *
 * <pre>{@code
 * BuffJsonDecoder decoder = BuffJson.decoder().setTypeRegistry(registry);
 *
 * MyMessage msg = decoder.decode(json, MyMessage.class);
 * MyMessage msg = decoder.decode(bytes, MyMessage.class);
 * MyMessage msg = decoder.decode(inputStream, MyMessage.class);
 * }</pre>
 *
 * @see BuffJson#decoder()
 */
public final class BuffJsonDecoder {

	private TypeRegistry typeRegistry;
	private boolean useGeneratedDecoders = true;

	BuffJsonDecoder() {
	}

	public BuffJsonDecoder setTypeRegistry(TypeRegistry registry) {
		this.typeRegistry = registry;
		return this;
	}

	public TypeRegistry getTypeRegistry() {
		return typeRegistry;
	}

	public BuffJsonDecoder setGeneratedDecoders(boolean enabled) {
		this.useGeneratedDecoders = enabled;
		return this;
	}

	public boolean getGeneratedDecoders() {
		return useGeneratedDecoders;
	}

	/**
	 * Decodes a proto3 JSON string to a Protocol Buffer message.
	 */
	public <T extends Message> T decode(String json, Class<T> messageClass) {
		setupThreadLocals();
		try {
			return JSON.parseObject(json, messageClass);
		} finally {
			clearThreadLocals();
		}
	}

	/**
	 * Decodes a proto3 JSON substring without allocating a new String. FastJson2
	 * reads the backing storage of the original String directly.
	 */
	public <T extends Message> T decode(String json, int offset, int length, Class<T> messageClass) {
		setupThreadLocals();
		try {
			return JSON.parseObject(json, offset, length, messageClass);
		} finally {
			clearThreadLocals();
		}
	}

	/**
	 * Decodes a UTF-8 JSON byte array to a Protocol Buffer message.
	 */
	public <T extends Message> T decode(byte[] json, Class<T> messageClass) {
		setupThreadLocals();
		try {
			return JSON.parseObject(json, messageClass);
		} finally {
			clearThreadLocals();
		}
	}

	/**
	 * Decodes a UTF-8 JSON byte array slice to a Protocol Buffer message. Zero-copy
	 * — FastJson2 reads directly from the provided array.
	 */
	public <T extends Message> T decode(byte[] json, int offset, int length, Class<T> messageClass) {
		setupThreadLocals();
		try {
			return JSON.parseObject(json, offset, length, messageClass);
		} finally {
			clearThreadLocals();
		}
	}

	/**
	 * Decodes proto3 JSON from an {@link InputStream} to a Protocol Buffer message.
	 */
	public <T extends Message> T decode(InputStream in, Class<T> messageClass) {
		setupThreadLocals();
		try {
			return JSON.parseObject(in, messageClass);
		} finally {
			clearThreadLocals();
		}
	}

	private void setupThreadLocals() {
		if (typeRegistry != null) {
			BuffJson.ACTIVE_REGISTRY.set(typeRegistry);
		}
		if (!useGeneratedDecoders) {
			BuffJson.SKIP_GENERATED.set(Boolean.TRUE);
		}
	}

	private void clearThreadLocals() {
		if (typeRegistry != null) {
			BuffJson.ACTIVE_REGISTRY.remove();
		}
		if (!useGeneratedDecoders) {
			BuffJson.SKIP_GENERATED.remove();
		}
	}
}
