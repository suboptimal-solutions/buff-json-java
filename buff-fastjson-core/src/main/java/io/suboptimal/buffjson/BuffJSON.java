package io.suboptimal.buffjson;

import com.alibaba.fastjson2.JSONFactory;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TypeRegistry;

import io.suboptimal.buffjson.internal.ProtobufWriterModule;

/**
 * Fast JSON serialization for Protocol Buffer messages.
 *
 * <p>
 * Produces JSON output compliant with the
 * <a href="https://protobuf.dev/programming-guides/proto3/#json">Proto3 JSON
 * spec</a>, matching
 * {@code JsonFormat.printer().omittingInsignificantWhitespace().print()} output
 * exactly.
 *
 * <h3>Simple usage</h3>
 *
 * <pre>{@code
 * String json = BuffJSON.encode(myProtoMessage);
 * }</pre>
 *
 * <h3>With Any type support</h3>
 *
 * <pre>{@code
 * Encoder encoder = BuffJSON.encoder()
 * 		.withTypeRegistry(TypeRegistry.newBuilder().add(MyMessage.getDescriptor()).build());
 *
 * String json = encoder.encode(messageContainingAny);
 * }</pre>
 *
 * <p>
 * Thread-safe. {@link Encoder} instances are immutable and can be cached.
 */
public final class BuffJSON {

	static {
		JSONFactory.getDefaultObjectWriterProvider().register(ProtobufWriterModule.INSTANCE);
	}

	/** ThreadLocal holding the active TypeRegistry for the current encode call. */
	public static final ThreadLocal<TypeRegistry> ACTIVE_REGISTRY = new ThreadLocal<>();

	private static final Encoder DEFAULT_ENCODER = new Encoder(null);

	private BuffJSON() {
	}

	/**
	 * Convenience method — encodes a message without a TypeRegistry. Equivalent to
	 * {@code BuffJSON.encoder().encode(message)}.
	 *
	 * <p>
	 * Throws if the message contains {@code google.protobuf.Any} fields. Use
	 * {@link #encoder()} with {@link Encoder#withTypeRegistry} for Any support.
	 */
	public static String encode(MessageOrBuilder message) {
		return DEFAULT_ENCODER.encode(message);
	}

	/** Creates a new {@link Encoder} for configuring serialization options. */
	public static Encoder encoder() {
		return DEFAULT_ENCODER;
	}
}
