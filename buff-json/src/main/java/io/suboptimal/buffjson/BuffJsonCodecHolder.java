package io.suboptimal.buffjson;

import com.google.protobuf.Message;

/**
 * Marker interface injected into protobuf message classes via protoc insertion
 * points. Provides direct access to the generated encoder and decoder for a
 * message type, replacing ServiceLoader-based discovery.
 *
 * <p>
 * At runtime, a simple {@code instanceof} check on the message instance
 * replaces the previous {@link java.util.ServiceLoader} scan.
 */
public interface BuffJsonCodecHolder {

	/**
	 * Returns the generated JSON encoder for this message type.
	 */
	BuffJsonGeneratedEncoder<? extends Message> buffJsonEncoder();

	/**
	 * Returns the generated JSON decoder for this message type.
	 */
	BuffJsonGeneratedDecoder<? extends Message> buffJsonDecoder();
}
