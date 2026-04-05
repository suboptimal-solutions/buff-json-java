package io.suboptimal.buffjson.jackson;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.Deserializers;
import com.fasterxml.jackson.databind.ser.Serializers;
import com.google.protobuf.Message;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonDecoder;
import io.suboptimal.buffjson.BuffJsonEncoder;

/**
 * Jackson module that enables proto3 JSON serialization/deserialization of
 * protobuf {@link Message} types through Jackson's {@code ObjectMapper}.
 *
 * <p>
 * This is a <b>thin wrapper</b> around buff-json's {@link BuffJsonEncoder} and
 * {@link BuffJsonDecoder} — not a reimplementation of proto3 JSON handling. The
 * serialization output is identical to
 * {@code JsonFormat.printer().omittingInsignificantWhitespace().print()}.
 *
 * <h3>Basic usage</h3>
 *
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper();
 * mapper.registerModule(new BuffJsonJacksonModule());
 *
 * // Protobuf messages work like any other Jackson type
 * String json = mapper.writeValueAsString(myProtoMessage);
 * MyMessage msg = mapper.readValue(json, MyMessage.class);
 *
 * // Including inside records and POJOs
 * record ApiResponse(String status, MyMessage data) {
 * }
 * mapper.writeValueAsString(new ApiResponse("ok", msg));
 * }</pre>
 *
 * <h3>Optimal deserialization performance</h3>
 *
 * <p>
 * For optimal deserialization performance, enable
 * {@link com.fasterxml.jackson.core.StreamReadFeature#INCLUDE_SOURCE_IN_LOCATION}
 * on the ObjectMapper. This allows the deserializer to extract raw JSON
 * substrings directly, avoiding expensive tree-to-string round-trips. Without
 * this feature, a slower fallback path is used and a warning is logged.
 *
 * <pre>{@code
 * ObjectMapper mapper = JsonMapper.builder().enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
 * 		.addModule(new BuffJsonJacksonModule()).build();
 * }</pre>
 *
 * <h3>Custom encoder/decoder (e.g. Any support)</h3>
 *
 * <p>
 * For messages containing {@code google.protobuf.Any} fields or other custom
 * settings, pass pre-configured encoder and decoder:
 *
 * <pre>{@code
 * var registry = TypeRegistry.newBuilder().add(MyMessage.getDescriptor()).build();
 * var encoder = BuffJson.encoder().setTypeRegistry(registry);
 * var decoder = BuffJson.decoder().setTypeRegistry(registry);
 * mapper.registerModule(new BuffJsonJacksonModule(encoder, decoder));
 * }</pre>
 *
 * <h3>Architecture</h3>
 *
 * <p>
 * Registers two resolver classes with Jackson:
 * <ul>
 * <li>{@link ProtobufSerializers} — returns a single
 * {@link ProtobufMessageSerializer} for all {@code Message} subtypes
 * <li>{@link ProtobufDeserializers} — creates a per-type
 * {@link ProtobufMessageDeserializer} (to know the target class)
 * </ul>
 *
 * <p>
 * The serializer uses
 * {@link com.fasterxml.jackson.core.JsonGenerator#writeRawValue(String)} to
 * inject the pre-serialized JSON from {@link BuffJsonEncoder#encode}. This
 * means {@code ObjectMapper.valueToTree()} does not produce a structured tree
 * for proto messages — use {@code writeValueAsString()} + {@code readTree()}
 * instead.
 *
 * @see ProtobufMessageSerializer
 * @see ProtobufMessageDeserializer
 */
public class BuffJsonJacksonModule extends com.fasterxml.jackson.databind.Module {

	private static final Version VERSION = new Version(0, 2, 0, "", "io.github.suboptimal-solutions",
			"buff-json-jackson");

	/** Encoder used by the serializer. */
	final BuffJsonEncoder encoder;

	/** Decoder used by the deserializer. */
	final BuffJsonDecoder decoder;

	/**
	 * Creates a module with default encoder and decoder. Sufficient for messages
	 * that do not contain {@code google.protobuf.Any} fields.
	 */
	public BuffJsonJacksonModule() {
		this(BuffJson.encoder(), BuffJson.decoder());
	}

	/**
	 * Creates a module with pre-configured encoder and decoder. Use this for custom
	 * settings such as {@link com.google.protobuf.TypeRegistry} for Any support.
	 *
	 * @param encoder
	 *            the encoder to use for serialization
	 * @param decoder
	 *            the decoder to use for deserialization
	 */
	public BuffJsonJacksonModule(BuffJsonEncoder encoder, BuffJsonDecoder decoder) {
		this.encoder = encoder;
		this.decoder = decoder;
	}

	@Override
	public String getModuleName() {
		return "BuffJsonJacksonModule";
	}

	@Override
	public Version version() {
		return VERSION;
	}

	/**
	 * Registers protobuf serializers and deserializers with Jackson's module
	 * system. Called by {@code ObjectMapper.registerModule()}.
	 */
	@Override
	public void setupModule(SetupContext context) {
		context.addSerializers(new ProtobufSerializers(encoder));
		context.addDeserializers(new ProtobufDeserializers(decoder));
	}

	/**
	 * Jackson serializer resolver that intercepts all {@link Message} subtypes.
	 *
	 * <p>
	 * Returns a single shared {@link ProtobufMessageSerializer} instance — since
	 * the serializer delegates to {@link BuffJsonEncoder#encode} which handles all
	 * message types dynamically, no per-type serializer is needed.
	 */
	static final class ProtobufSerializers extends Serializers.Base {

		private final ProtobufMessageSerializer serializer;

		ProtobufSerializers(BuffJsonEncoder encoder) {
			this.serializer = new ProtobufMessageSerializer(encoder);
		}

		@Override
		public JsonSerializer<?> findSerializer(SerializationConfig config, JavaType type, BeanDescription beanDesc) {
			if (Message.class.isAssignableFrom(type.getRawClass())) {
				return serializer;
			}
			return null;
		}
	}

	/**
	 * Jackson deserializer resolver that intercepts all {@link Message} subtypes.
	 *
	 * <p>
	 * Unlike the serializer, a new {@link ProtobufMessageDeserializer} is created
	 * per resolved type because the deserializer needs to know the concrete
	 * {@code Message} class to call {@link BuffJsonDecoder#decode(String, Class)}.
	 * Jackson caches the resolved deserializer per type, so this factory method is
	 * called at most once per message class.
	 */
	static final class ProtobufDeserializers extends Deserializers.Base {

		private final BuffJsonDecoder decoder;

		ProtobufDeserializers(BuffJsonDecoder decoder) {
			this.decoder = decoder;
		}

		@Override
		public JsonDeserializer<?> findBeanDeserializer(JavaType type, DeserializationConfig config,
				BeanDescription beanDesc) {
			if (Message.class.isAssignableFrom(type.getRawClass())) {
				@SuppressWarnings("unchecked")
				Class<? extends Message> messageClass = (Class<? extends Message>) type.getRawClass();
				return new ProtobufMessageDeserializer(decoder, messageClass);
			}
			return null;
		}
	}
}
