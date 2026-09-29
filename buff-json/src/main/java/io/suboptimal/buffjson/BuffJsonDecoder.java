package io.suboptimal.buffjson;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.modules.ObjectReaderModule;
import com.alibaba.fastjson2.util.JDKUtils;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;
import com.google.protobuf.TypeRegistry;

import io.suboptimal.buffjson.internal.FastInput;
import io.suboptimal.buffjson.internal.GeneratedDecoderRegistry;
import io.suboptimal.buffjson.internal.ProtobufMessageReader;
import io.suboptimal.buffjson.internal.ProtobufReaderModule;
import io.suboptimal.buffjson.internal.codegen.RuntimeCodegen;

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
 * <h2>Thread-safety</h2>
 *
 * Once configured, a decoder is safe to share across threads: each
 * {@code decode} call creates a fresh {@link JSONReader}, the cached
 * {@link ProtobufMessageReader} has only {@code final} fields, and the
 * underlying schema caches are concurrent.
 *
 * <p>
 * Mutating setters ({@link #setTypeRegistry}, {@link #setGeneratedDecoders},
 * {@link #setTypedAccessors}) are <b>not</b> safe to call concurrently with
 * {@code decode} — a setter racing with an in-flight decode can result in the
 * cached reader holding a stale config. Configure the decoder once at startup,
 * then share it.
 *
 * @see BuffJson#decoder()
 */
public final class BuffJsonDecoder {

	/**
	 * Whether new decoders generate readers at run time; {@code false} unless
	 * {@code -Dbuffjson.runtimeCodegen=true}.
	 */
	private static final boolean DEFAULT_RUNTIME_CODEGEN = Boolean.getBoolean("buffjson.runtimeCodegen");

	private TypeRegistry typeRegistry;
	private boolean useGeneratedDecoders = true;
	private boolean useTypedAccessors = true;
	private boolean useFastPath = true;
	private boolean useRuntimeCodegen = DEFAULT_RUNTIME_CODEGEN;
	private volatile ProtobufMessageReader cachedReader;

	BuffJsonDecoder() {
	}

	public BuffJsonDecoder setTypeRegistry(TypeRegistry registry) {
		this.typeRegistry = registry;
		this.cachedReader = null;
		return this;
	}

	public TypeRegistry getTypeRegistry() {
		return typeRegistry;
	}

	public BuffJsonDecoder setGeneratedDecoders(boolean enabled) {
		this.useGeneratedDecoders = enabled;
		this.cachedReader = null;
		return this;
	}

	public boolean getGeneratedDecoders() {
		return useGeneratedDecoders;
	}

	/**
	 * Enables cached typed builder accessors for runtime decoding (default: true).
	 * Disable together with generated decoders to exercise the descriptor fallback.
	 */
	public BuffJsonDecoder setTypedAccessors(boolean enabled) {
		this.useTypedAccessors = enabled;
		this.cachedReader = null;
		return this;
	}

	public boolean getTypedAccessors() {
		return useTypedAccessors;
	}

	/**
	 * Enables the canonical-input fast path (default: true). With generated
	 * decoders enabled, {@code byte[]} and {@code String} input is first read by a
	 * cursor that understands only plain canonical proto3 JSON; if the document is
	 * anything else (escaped names, non-canonical numbers, unknown enum names,
	 * well-known types it does not handle, malformed input, ...) the whole document
	 * is decoded again by the general decoder, so results and errors are exactly
	 * those of the general decoder. Disable to force the general decoder.
	 */
	public BuffJsonDecoder setFastPath(boolean enabled) {
		this.useFastPath = enabled;
		return this;
	}

	public boolean getFastPath() {
		return useFastPath;
	}

	/**
	 * Enables generating decoders at run time (default: false, or the system
	 * property {@code buffjson.runtimeCodegen}) for message classes that have none
	 * from the protoc plugin, on a JVM that can do it (Java 24+, which has the
	 * Class-File API). The generated decoder is the canonical-input reader the
	 * plugin would have generated, so such messages get the fast path too; the
	 * general decoding of anything that is not canonical is unchanged. If
	 * generation is not possible for a message class, it is decoded as without this
	 * option.
	 *
	 * <p>
	 * Together with {@link #setGeneratedDecoders} disabled, nested messages are
	 * generated too, ignoring the plugin's decoders.
	 */
	public BuffJsonDecoder setRuntimeCodegen(boolean enabled) {
		this.useRuntimeCodegen = enabled;
		return this;
	}

	public boolean getRuntimeCodegen() {
		return useRuntimeCodegen;
	}

	/** Whether input is first offered to a canonical-input reader. */
	private boolean fastEnabled() {
		return useFastPath && (useGeneratedDecoders || useRuntimeCodegen);
	}

	/**
	 * Decodes a proto3 JSON string to a Protocol Buffer message.
	 */
	public <T extends Message> T decode(String json, Class<T> messageClass) {
		if (json == null || json.isEmpty()) {
			return null;
		}
		if (fastEnabled()) {
			byte[] latin1 = latin1Bytes(json);
			if (latin1 != null) {
				T fast = decodeFast(latin1, 0, latin1.length, true, messageClass);
				if (fast != null) {
					return fast;
				}
			}
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
		if (fastEnabled() && offset >= 0 && length > 0 && offset <= json.length() - length) {
			byte[] latin1 = latin1Bytes(json);
			if (latin1 != null) {
				T fast = decodeFast(latin1, offset, length, true, messageClass);
				if (fast != null) {
					return fast;
				}
			}
		}
		try (JSONReader reader = JSONReader.of(json, offset, length)) {
			return readProto(reader, messageClass);
		}
	}

	/**
	 * Decodes a UTF-8 JSON byte array to a Protocol Buffer message.
	 */
	public <T extends Message> T decode(byte[] json, Class<T> messageClass) {
		if (fastEnabled() && json != null) {
			T fast = decodeFast(json, 0, json.length, false, messageClass);
			if (fast != null) {
				return fast;
			}
		}
		try (JSONReader reader = JSONReader.of(json)) {
			return readProto(reader, messageClass);
		}
	}

	/**
	 * Decodes a UTF-8 JSON byte array slice to a Protocol Buffer message. Zero-copy
	 * — FastJson2 reads directly from the provided array.
	 */
	public <T extends Message> T decode(byte[] json, int offset, int length, Class<T> messageClass) {
		if (fastEnabled() && json != null) {
			T fast = decodeFast(json, offset, length, false, messageClass);
			if (fast != null) {
				return fast;
			}
		}
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

	/**
	 * Per message class: its generated decoder, or none (then the fast path is
	 * skipped).
	 */
	private static final ClassValue<BuffJsonGeneratedDecoder<Message>> FAST_DECODERS = new ClassValue<>() {
		@Override
		@SuppressWarnings("unchecked")
		protected BuffJsonGeneratedDecoder<Message> computeValue(Class<?> type) {
			try {
				Message defaultInstance = ProtobufMessageReader.getDefaultInstance(type);
				if (defaultInstance instanceof BuffJsonCodecHolder holder) {
					var decoder = (BuffJsonGeneratedDecoder<Message>) holder.buffJsonDecoder();
					// same side effect as the general path: descriptor-only nested reads find it
					GeneratedDecoderRegistry.put(defaultInstance.getDescriptorForType(), decoder);
					return decoder;
				}
			} catch (RuntimeException notAMessageClass) {
				// the general path reports it
			}
			return null;
		}
	};

	/**
	 * Tries the canonical-input fast path. Returns {@code null} whenever the input
	 * is anything but plain canonical JSON (or there is no generated decoder); the
	 * caller then decodes it the general way, which alone decides errors.
	 */
	@SuppressWarnings("unchecked")
	private <T extends Message> T decodeFast(byte[] json, int offset, int length, boolean latin1,
			Class<T> messageClass) {
		BuffJsonGeneratedDecoder<Message> decoder = useGeneratedDecoders ? FAST_DECODERS.get(messageClass) : null;
		if (decoder == null && useRuntimeCodegen) {
			decoder = RuntimeCodegen.fastDecoder(messageClass, useGeneratedDecoders);
		}
		if (decoder == null) {
			return null;
		}
		try {
			// Generated readers index the array directly and may look at bytes past the
			// end of a slice. A document that needed them ends beyond the slice, which
			// finished() reports, and the general decoder then rules on it.
			FastInput in = new FastInput(json, offset, length, latin1).allowRuntimeCodegen(useRuntimeCodegen);
			T message = (T) decoder.readFast(in);
			return in.finished() ? message : null;
		} catch (FastInput.Bail | IndexOutOfBoundsException bail) {
			// a read past the end of the array is a truncated document
			return null;
		}
	}

	/**
	 * The backing array of a Latin-1 coded {@code String}, or {@code null} if the
	 * string is not Latin-1 coded (it may then hold surrogates the byte cursor must
	 * not see as text) or fastjson2 cannot expose the array on this JVM.
	 */
	private static byte[] latin1Bytes(String json) {
		var coder = JDKUtils.STRING_CODER;
		var value = JDKUtils.STRING_VALUE;
		if (coder != null && value != null && coder.applyAsInt(json) == JDKUtils.LATIN1) {
			return value.apply(json);
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private <T extends Message> T readProto(JSONReader reader, Class<T> messageClass) {
		if (reader.nextIfNull()) {
			// proto3 JSON: a message is never representable as a bare top-level `null`
			// (null is only a field value meaning "absent", or a wrapped NullValue), so
			// reject it rather than returning a null Message. Empty input (a null/empty
			// Java string/byte[]) is short-circuited by the public decode methods and is a
			// separate, lenient convenience — only the literal `null` reaches here.
			throw new JSONException(reader.info("Top-level null is not a valid proto3 JSON message"));
		}
		Message defaultInstance = ProtobufMessageReader.getDefaultInstance(messageClass);
		if (reader.isEnd()) {
			// empty or whitespace-only document: the lenient default instance, as before
			return (T) defaultInstance;
		}
		Descriptor descriptor = defaultInstance.getDescriptorForType();
		T result = (T) messageReader().readMessage(reader, descriptor, defaultInstance);
		if (!reader.isEnd()) {
			throw new JSONException(reader.info("input not end"));
		}
		return result;
	}

	private ProtobufMessageReader messageReader() {
		var r = cachedReader;
		if (r == null) {
			r = new ProtobufMessageReader(typeRegistry, useGeneratedDecoders, useTypedAccessors);
			cachedReader = r;
		}
		return r;
	}
}
