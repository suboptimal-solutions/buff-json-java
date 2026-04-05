package io.suboptimal.buffjson.jackson;

import java.io.IOException;
import java.io.StringWriter;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.google.protobuf.Message;

import io.suboptimal.buffjson.BuffJsonDecoder;

/**
 * Jackson deserializer for protobuf {@link Message} types.
 *
 * <p>
 * Extracts the raw JSON substring from Jackson's parser and delegates to
 * {@link BuffJsonDecoder#decode(String, Class)} for actual proto3 JSON parsing.
 * This avoids building a {@code JsonNode} tree and re-serializing it — Jackson
 * only identifies the object boundaries via {@link JsonParser#skipChildren()},
 * and buff-json's fast decoder handles the actual parsing in a single pass.
 *
 * <p>
 * <b>Required:</b> The owning {@code ObjectMapper} must be created with
 * {@link StreamReadFeature#INCLUDE_SOURCE_IN_LOCATION} enabled for the fast
 * path to activate. Without it, a slower streaming fallback is used. See
 * {@link ProtobufJacksonModule} for configuration details.
 *
 * <p>
 * A separate instance is created per target {@code Message} class (by
 * {@link ProtobufJacksonModule.ProtobufDeserializers}) because the deserializer
 * needs to know the concrete class to call {@link BuffJsonDecoder#decode}.
 * Jackson caches resolved deserializers, so this is a one-time cost per message
 * type.
 *
 * @see ProtobufJacksonModule.ProtobufDeserializers
 */
final class ProtobufMessageDeserializer extends JsonDeserializer<Message> {

	private static final Logger LOG = Logger.getLogger(ProtobufMessageDeserializer.class.getName());

	private static volatile boolean sourceWarningLogged;

	/**
	 * BuffJsonDecoder instance, potentially configured with a TypeRegistry for Any.
	 */
	private final BuffJsonDecoder decoder;

	/** The concrete Message subclass to deserialize into. */
	private final Class<? extends Message> messageClass;

	ProtobufMessageDeserializer(BuffJsonDecoder decoder, Class<? extends Message> messageClass) {
		this.decoder = decoder;
		this.messageClass = messageClass;
	}

	/**
	 * Deserializes a proto3 JSON object from the Jackson parser.
	 *
	 * <p>
	 * <b>Fast path</b> (when {@link StreamReadFeature#INCLUDE_SOURCE_IN_LOCATION}
	 * is enabled): records the char offset of the opening {@code &#123;}, calls
	 * {@link JsonParser#skipChildren()} to advance past the matching
	 * {@code &#125;}, extracts the raw JSON substring, and delegates to buff-json's
	 * decoder. Jackson never builds a tree or extracts values — it only identifies
	 * object boundaries.
	 *
	 * <p>
	 * <b>Fallback</b> (stream input or feature disabled): streams tokens to a
	 * {@link StringWriter} via
	 * {@link JsonGenerator#copyCurrentStructure(JsonParser)}, avoiding
	 * {@code JsonNode} tree allocation.
	 *
	 * @param parser
	 *            the Jackson JSON parser positioned at the start of the message
	 *            object
	 * @param ctxt
	 *            the deserialization context
	 * @return the deserialized protobuf message
	 */
	@Override
	public Message deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
		// Fast path: extract raw JSON substring when source string is available.
		// Requires StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION on the ObjectMapper.
		Object source = parser.getInputSource();
		if (source instanceof String rawJson) {
			long start = parser.currentTokenLocation().getCharOffset();
			parser.skipChildren();
			long end = parser.currentLocation().getCharOffset();
			try {
				return decoder.decode(rawJson.substring((int) start, (int) end), messageClass);
			} catch (Exception e) {
				throw new IOException(
						"Failed to decode protobuf " + messageClass.getSimpleName() + ": " + e.getMessage(), e);
			}
		}

		// Warn once if fast path is not available
		if (!sourceWarningLogged) {
			sourceWarningLogged = true;
			LOG.warning("StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION is not enabled on the ObjectMapper. "
					+ "Protobuf deserialization will use a slower fallback path. "
					+ "Enable it via: JsonMapper.builder().enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)");
		}

		// Fallback: stream tokens directly to a StringWriter (no JsonNode tree).
		StringWriter sw = new StringWriter(256);
		try (JsonGenerator gen = parser.getCodec().getFactory().createGenerator(sw)) {
			gen.copyCurrentStructure(parser);
		}
		try {
			return decoder.decode(sw.toString(), messageClass);
		} catch (Exception e) {
			throw new IOException("Failed to decode protobuf " + messageClass.getSimpleName() + ": " + e.getMessage(),
					e);
		}
	}

	@Override
	public Class<? extends Message> handledType() {
		return messageClass;
	}
}
