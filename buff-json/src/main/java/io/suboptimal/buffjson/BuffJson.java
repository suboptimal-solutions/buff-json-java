package io.suboptimal.buffjson;

import com.alibaba.fastjson2.JSONFactory;
import com.google.protobuf.TypeRegistry;

import io.suboptimal.buffjson.internal.ProtobufReaderModule;
import io.suboptimal.buffjson.internal.ProtobufWriterModule;

/**
 * Entry point for fast proto3 JSON serialization and deserialization.
 *
 * <h3>Serialization</h3>
 *
 * <pre>{@code
 * BuffJsonEncoder encoder = BuffJson.encoder();
 * String json = encoder.encode(myProtoMessage);
 * byte[] bytes = encoder.encodeToBytes(myProtoMessage);
 * encoder.encode(myProtoMessage, outputStream);
 * }</pre>
 *
 * <h3>Deserialization</h3>
 *
 * <pre>{@code
 * BuffJsonDecoder decoder = BuffJson.decoder();
 * MyMessage msg = decoder.decode(json, MyMessage.class);
 * MyMessage msg = decoder.decode(bytes, MyMessage.class);
 * MyMessage msg = decoder.decode(inputStream, MyMessage.class);
 * }</pre>
 *
 * <h3>With Any type support</h3>
 *
 * <pre>{@code
 * BuffJsonEncoder encoder = BuffJson.encoder()
 * 		.setTypeRegistry(TypeRegistry.newBuilder().add(MyMessage.getDescriptor()).build());
 * }</pre>
 */
public final class BuffJson {

	static {
		JSONFactory.getDefaultObjectWriterProvider().register(ProtobufWriterModule.INSTANCE);
		JSONFactory.getDefaultObjectReaderProvider().register(ProtobufReaderModule.INSTANCE);
	}

	/** ThreadLocal holding the active TypeRegistry for the current encode call. */
	public static final ThreadLocal<TypeRegistry> ACTIVE_REGISTRY = new ThreadLocal<>();

	/**
	 * When set to {@code true}, generated encoders and decoders are bypassed and
	 * the runtime reflection path is used.
	 */
	public static final ThreadLocal<Boolean> SKIP_GENERATED = new ThreadLocal<>();

	private BuffJson() {
	}

	/**
	 * Creates a new {@link BuffJsonEncoder} for configuring and performing
	 * serialization.
	 */
	public static BuffJsonEncoder encoder() {
		return new BuffJsonEncoder();
	}

	/**
	 * Creates a new {@link BuffJsonDecoder} for configuring and performing
	 * deserialization.
	 */
	public static BuffJsonDecoder decoder() {
		return new BuffJsonDecoder();
	}
}
