package io.suboptimal.buffjson;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.modules.ObjectReaderModule;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;
import com.google.protobuf.TypeRegistry;

import io.suboptimal.buffjson.internal.ProtobufMessageReader;
import io.suboptimal.buffjson.internal.ProtobufReaderModule;

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
		if (json == null || json.isEmpty()) {
			return null;
		}
		try (JSONReader reader = JSONReader.of(json)) {
			return readProto(reader, messageClass);
		}
	}

	/**
	 * Decodes a proto3 JSON substring without allocating a new String. FastJson2
	 * reads the backing storage of the original String directly.
	 */
	public <T extends Message> T decode(String json, int offset, int length, Class<T> messageClass) {
		if (json == null || length == 0) {
			return null;
		}
		try (JSONReader reader = JSONReader.of(json, offset, length)) {
			return readProto(reader, messageClass);
		}
	}

	/**
	 * Decodes a UTF-8 JSON byte array to a Protocol Buffer message.
	 */
	public <T extends Message> T decode(byte[] json, Class<T> messageClass) {
		try (JSONReader reader = JSONReader.of(json)) {
			return readProto(reader, messageClass);
		}
	}

	/**
	 * Decodes a UTF-8 JSON byte array slice to a Protocol Buffer message. Zero-copy
	 * — FastJson2 reads directly from the provided array.
	 */
	public <T extends Message> T decode(byte[] json, int offset, int length, Class<T> messageClass) {
		try (JSONReader reader = JSONReader.of(json, offset, length)) {
			return readProto(reader, messageClass);
		}
	}

	/**
	 * Decodes proto3 JSON from an {@link InputStream} to a Protocol Buffer message.
	 */
	public <T extends Message> T decode(InputStream in, Class<T> messageClass) {
		try (JSONReader reader = JSONReader.of(in, StandardCharsets.UTF_8)) {
			return readProto(reader, messageClass);
		}
	}

	/**
	 * Returns a fastjson2 reader module configured with this decoder's settings.
	 * Register it for mixed pojo + protobuf deserialization:
	 *
	 * <pre>{@code
	 * JSONFactory.getDefaultObjectReaderProvider().register(decoder.readerModule());
	 * JSON.parseObject(json, MyMessage.class); // uses this decoder's settings
	 * }</pre>
	 */
	public ObjectReaderModule readerModule() {
		return new ProtobufReaderModule(messageReader());
	}

	@SuppressWarnings("unchecked")
	private <T extends Message> T readProto(JSONReader reader, Class<T> messageClass) {
		if (reader.nextIfNull()) {
			return null;
		}
		Message defaultInstance = ProtobufMessageReader.getDefaultInstance(messageClass);
		Descriptor descriptor = defaultInstance.getDescriptorForType();
		T result = (T) messageReader().readMessage(reader, descriptor, defaultInstance);
		if (!reader.isEnd()) {
			throw new JSONException("input not end");
		}
		return result;
	}

	private ProtobufMessageReader messageReader() {
		return new ProtobufMessageReader(typeRegistry, useGeneratedDecoders);
	}
}
