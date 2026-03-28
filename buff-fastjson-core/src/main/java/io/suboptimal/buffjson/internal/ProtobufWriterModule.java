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
 * Registered once via
 * {@link com.alibaba.fastjson2.JSONFactory#getDefaultObjectWriterProvider()}.
 * When fastjson2 encounters any class assignable to {@code Message}, this
 * module returns the singleton {@link ProtobufMessageWriter} instance.
 */
public final class ProtobufWriterModule implements ObjectWriterModule {

	public static final ProtobufWriterModule INSTANCE = new ProtobufWriterModule();

	private ProtobufWriterModule() {
	}

	@SuppressWarnings("rawtypes") // ObjectWriterModule declares raw Class
	@Override
	public ObjectWriter<?> getObjectWriter(Type objectType, Class objectClass) {
		if (objectClass != null && Message.class.isAssignableFrom(objectClass)) {
			return ProtobufMessageWriter.INSTANCE;
		}
		return null;
	}
}
