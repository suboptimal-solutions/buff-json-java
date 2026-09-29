package io.suboptimal.buffjson;

import com.google.protobuf.Message;

/**
 * The canonical-input fast path of the decoders the protoc plugin generated:
 * see {@link FastPathTestBase} for what is checked.
 */
class BuffJsonFastPathTest extends FastPathTestBase {

	private static final BuffJsonDecoder FAST = BuffJson.decoder();

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
		return readFastCompiled(defaultInstance, json, latin1);
	}
}
