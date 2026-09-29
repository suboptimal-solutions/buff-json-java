package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.Message;

import io.suboptimal.buffjson.internal.codegen.RuntimeCodegen;

/**
 * The same checks again, on a reader generated at run time for the message at
 * the top whose nested messages are read by the plugin's decoders: the two
 * kinds of generated decoder calling each other.
 */
class BuffJsonMixedFastPathTest extends FastPathTestBase {

	private static final BuffJsonDecoder FAST = BuffJson.decoder().setRuntimeCodegen(true);

	@Override
	protected boolean available() {
		return RuntimeCodegen.available();
	}

	private static final BuffJsonDecoder GENERAL = BuffJson.decoder().setFastPath(false);

	@Override
	protected BuffJsonDecoder fast() {
		return FAST;
	}

	@Override
	protected BuffJsonDecoder general() {
		return GENERAL;
	}

	@Override
	protected Message readFast(Message defaultInstance, byte[] json, boolean latin1) {
		Class<?> type = defaultInstance.getClass();
		var decoder = RuntimeCodegen.fastDecoder(type, true);
		assertNotNull(decoder,
				"no generated decoder for " + type.getName() + ": " + RuntimeCodegen.failure(type, true));
		assertTrue(decoder.getClass().getName().endsWith("$$BuffJsonFast"));
		return run(decoder, json, latin1);
	}
}
