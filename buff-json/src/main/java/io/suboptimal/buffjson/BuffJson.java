package io.suboptimal.buffjson;

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
 *
 * <h3>Mixed pojo + protobuf (fastjson2 registration)</h3>
 *
 * <pre>{@code
 * BuffJsonEncoder encoder = BuffJson.encoder();
 * JSONFactory.getDefaultObjectWriterProvider().register(encoder.writerModule());
 *
 * BuffJsonDecoder decoder = BuffJson.decoder();
 * JSONFactory.getDefaultObjectReaderProvider().register(decoder.readerModule());
 * }</pre>
 */
public final class BuffJson {

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
