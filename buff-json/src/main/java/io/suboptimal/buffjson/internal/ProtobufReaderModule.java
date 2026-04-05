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
 * The module holds a configured {@link ProtobufMessageReader} instance that
 * carries settings (typeRegistry, useGenerated). Obtain a module via
 * {@link io.suboptimal.buffjson.BuffJsonDecoder#readerModule()}.
 */
public final class ProtobufReaderModule implements ObjectReaderModule {

	public static final ProtobufReaderModule INSTANCE = new ProtobufReaderModule(ProtobufMessageReader.INSTANCE);

	private final ProtobufMessageReader reader;

	public ProtobufReaderModule(ProtobufMessageReader reader) {
		this.reader = reader;
	}

	@Override
	public ObjectReader<?> getObjectReader(Type objectType) {
		if (objectType instanceof Class<?> clazz && Message.class.isAssignableFrom(clazz)) {
			return reader;
		}
		return null;
	}
}
