package io.suboptimal.buffjson;

import java.io.IOException;
import java.io.OutputStream;

import com.alibaba.fastjson2.JSONFactory;
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
 * <h2>Thread-safety</h2>
 *
 * Once configured, an encoder is safe to share across threads:
 * {@code encode}/{@code encodeToBytes} create a fresh {@link JSONWriter} per
 * call, the cached {@link ProtobufMessageWriter} has only {@code final} fields,
 * and the underlying schema caches are concurrent.
 *
 * <p>
 * Mutating setters ({@link #setTypeRegistry}, {@link #setGeneratedEncoders},
 * {@link #setTypedAccessors}) are <b>not</b> safe to call concurrently with
 * {@code encode} — a setter racing with an in-flight encode can result in the
 * cached writer holding a stale config. Configure the encoder once at startup,
 * then share it.
 *
 * @see BuffJson#encoder()
 */
public final class BuffJsonEncoder {

	private TypeRegistry typeRegistry;
	private boolean useGeneratedEncoders = true;
	private boolean useTypedAccessors = true;
	private volatile ProtobufMessageWriter cachedWriter;

	/**
	 * Shared write context. {@code JSONWriter.of()} otherwise allocates a fresh
	 * {@link JSONWriter.Context} per call to carry configuration that never changes
	 * between encodes. The context is read-only during writing, so one per encoder
	 * is safe to share across threads.
	 */
	private final JSONWriter.Context writeContext = JSONFactory.createWriteContext();

	BuffJsonEncoder() {
	}

	public BuffJsonEncoder setTypeRegistry(TypeRegistry registry) {
		this.typeRegistry = registry;
		this.cachedWriter = null;
		return this;
	}

	public TypeRegistry getTypeRegistry() {
		return typeRegistry;
	}

	public BuffJsonEncoder setGeneratedEncoders(boolean enabled) {
		this.useGeneratedEncoders = enabled;
		this.cachedWriter = null;
		return this;
	}

	public boolean getGeneratedEncoders() {
		return useGeneratedEncoders;
	}

	/**
	 * Toggles the LambdaMetafactory-based typed-accessor runtime path. When false,
	 * messages without a generated encoder fall through to the pure-reflection
	 * {@code MessageSchema} + {@code getField} path. Intended for benchmarking and
	 * test-suite path coverage; production code should leave this enabled.
	 */
	public BuffJsonEncoder setTypedAccessors(boolean enabled) {
		this.useTypedAccessors = enabled;
		this.cachedWriter = null;
		return this;
	}

	public boolean getTypedAccessors() {
		return useTypedAccessors;
	}

	/**
	 * Encodes a Protocol Buffer message to its proto3 JSON string.
	 */
	public String encode(MessageOrBuilder message) {
		Message msg = toMessage(message);
		try (JSONWriter writer = JSONWriter.of(writeContext)) {
			messageWriter().writeMessage(writer, msg);
			return writer.toString();
		}
	}

	/**
	 * Encodes a Protocol Buffer message to a UTF-8 JSON byte array.
	 */
	public byte[] encodeToBytes(MessageOrBuilder message) {
		Message msg = toMessage(message);
		try (JSONWriter writer = JSONWriter.ofUTF8(writeContext)) {
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
		try (JSONWriter writer = JSONWriter.ofUTF8(writeContext)) {
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
		var w = cachedWriter;
		if (w == null) {
			w = new ProtobufMessageWriter(typeRegistry, useGeneratedEncoders, useTypedAccessors);
			cachedWriter = w;
		}
		return w;
	}

	private static Message toMessage(MessageOrBuilder message) {
		if (message instanceof Message m) {
			return m;
		}
		return ((Message.Builder) message).buildPartial();
	}
}
