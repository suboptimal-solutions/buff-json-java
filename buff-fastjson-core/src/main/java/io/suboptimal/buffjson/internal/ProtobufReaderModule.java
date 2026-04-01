package io.suboptimal.buffjson.internal;

import java.lang.reflect.Type;

import com.alibaba.fastjson2.modules.ObjectReaderModule;
import com.alibaba.fastjson2.reader.ObjectReader;
import com.google.protobuf.Message;

/**
 * fastjson2 {@link ObjectReaderModule} that intercepts all {@link Message}
 * subclass types and delegates deserialization to
 * {@link ProtobufMessageReader}.
 *
 * <p>
 * Registered once via
 * {@link com.alibaba.fastjson2.JSONFactory#getDefaultObjectReaderProvider()}.
 * When fastjson2 encounters any class assignable to {@code Message}, this
 * module returns the singleton {@link ProtobufMessageReader} instance.
 */
public final class ProtobufReaderModule implements ObjectReaderModule {

	public static final ProtobufReaderModule INSTANCE = new ProtobufReaderModule();

	private ProtobufReaderModule() {
	}

	@Override
	public ObjectReader<?> getObjectReader(Type objectType) {
		if (objectType instanceof Class<?> clazz && Message.class.isAssignableFrom(clazz)) {
			return ProtobufMessageReader.INSTANCE;
		}
		return null;
	}
}
