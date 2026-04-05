package io.suboptimal.buffjson;

import java.io.IOException;
import java.io.OutputStream;

import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.modules.ObjectWriterModule;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TypeRegistry;

import io.suboptimal.buffjson.internal.ProtobufMessageWriter;
import io.suboptimal.buffjson.internal.ProtobufWriterModule;

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
		try (JSONWriter writer = JSONWriter.of()) {
			messageWriter().writeMessage(writer, msg);
			return writer.toString();
		}
	}

	/**
	 * Encodes a Protocol Buffer message to a UTF-8 JSON byte array.
	 */
	public byte[] encodeToBytes(MessageOrBuilder message) {
		Message msg = toMessage(message);
		try (JSONWriter writer = JSONWriter.ofUTF8()) {
			messageWriter().writeMessage(writer, msg);
			return writer.getBytes();
		}
	}

	/**
	 * Encodes a Protocol Buffer message and writes the JSON directly to an
	 * {@link OutputStream}.
	 */
	public void encode(MessageOrBuilder message, OutputStream out) throws IOException {
		Message msg = toMessage(message);
		try (JSONWriter writer = JSONWriter.ofUTF8()) {
			messageWriter().writeMessage(writer, msg);
			writer.flushTo(out);
		}
	}

	/**
	 * Returns a fastjson2 writer module configured with this encoder's settings.
	 * Register it for mixed pojo + protobuf serialization:
	 *
	 * <pre>{@code
	 * JSONFactory.getDefaultObjectWriterProvider().register(encoder.writerModule());
	 * JSON.toJSONString(message); // uses this encoder's settings
	 * }</pre>
	 */
	public ObjectWriterModule writerModule() {
		return new ProtobufWriterModule(messageWriter());
	}

	private ProtobufMessageWriter messageWriter() {
		return new ProtobufMessageWriter(typeRegistry, useGeneratedEncoders);
	}

	private static Message toMessage(MessageOrBuilder message) {
		if (message instanceof Message m) {
			return m;
		}
		return ((Message.Builder) message).buildPartial();
	}
}
