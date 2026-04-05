package io.suboptimal.buffjson.internal;

import java.lang.reflect.Type;

import com.alibaba.fastjson2.modules.ObjectWriterModule;
import com.alibaba.fastjson2.writer.ObjectWriter;
import com.google.protobuf.Message;

/**
 * fastjson2 {@link ObjectWriterModule} that intercepts all {@link Message}
 * subclass types and delegates serialization to {@link ProtobufMessageWriter}.
 *
 * <p>
 * The module holds a configured {@link ProtobufMessageWriter} instance that
 * carries settings (typeRegistry, useGenerated). Obtain a module via
 * {@link io.suboptimal.buffjson.BuffJsonEncoder#writerModule()}.
 */
public final class ProtobufWriterModule implements ObjectWriterModule {

	public static final ProtobufWriterModule INSTANCE = new ProtobufWriterModule(ProtobufMessageWriter.INSTANCE);

	private final ProtobufMessageWriter writer;

	public ProtobufWriterModule(ProtobufMessageWriter writer) {
		this.writer = writer;
	}

	@SuppressWarnings("rawtypes") // ObjectWriterModule declares raw Class
	@Override
	public ObjectWriter<?> getObjectWriter(Type objectType, Class objectClass) {
		if (objectClass != null && Message.class.isAssignableFrom(objectClass)) {
			return writer;
		}
		return null;
	}
}
