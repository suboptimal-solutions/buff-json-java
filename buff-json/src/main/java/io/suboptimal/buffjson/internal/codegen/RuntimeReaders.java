package io.suboptimal.buffjson.internal.codegen;

import com.google.protobuf.Message;

import io.suboptimal.buffjson.BuffJsonCodecHolder;
import io.suboptimal.buffjson.BuffJsonGeneratedDecoder;
import io.suboptimal.buffjson.internal.FastInput;
import io.suboptimal.buffjson.internal.ProtobufMessageReader;

/**
 * Finds, when a document is being read, the reader of a nested message whose
 * class was not known to the code that reads its parent.
 *
 * <p>
 * That is a message from a jar the plugin never ran on (a Google API type, a
 * shared library) or from another module built with the plugin: the parent's
 * generated code cannot name a decoder class for it, and before this it gave up
 * on every document that contained one. Now it calls {@link #readFast}, which
 * uses the message's plugin-generated decoder if the class has one and
 * otherwise a reader generated at run time -- if the decoder that started the
 * read allowed that -- and gives up as before if neither is available.
 */
public final class RuntimeReaders {

	private RuntimeReaders() {
	}

	/**
	 * Reads the message at the cursor's position as a {@code type}; throws the
	 * cursor's bail-out where no reader is available.
	 */
	public static Message readFast(FastInput in, Class<?> type) {
		if (ProtobufMessageReader.getDefaultInstance(type) instanceof BuffJsonCodecHolder holder) {
			return holder.buffJsonDecoder().readFast(in);
		}
		if (!in.runtimeCodegen()) {
			throw FastInput.bail();
		}
		BuffJsonGeneratedDecoder<Message> decoder = RuntimeCodegen.fastDecoder(type, true);
		if (decoder == null) {
			throw FastInput.bail();
		}
		return decoder.readFast(in);
	}
}
