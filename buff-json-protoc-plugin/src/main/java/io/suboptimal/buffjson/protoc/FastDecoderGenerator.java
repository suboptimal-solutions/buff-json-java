package io.suboptimal.buffjson.protoc;

import java.util.List;
import java.util.Map;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

import io.suboptimal.buffjson.internal.codegen.FastReaderTemplate;
import io.suboptimal.buffjson.internal.codegen.Members;
import io.suboptimal.buffjson.internal.codegen.SourcePrinter;
import io.suboptimal.buffjson.internal.codegen.Ty;

/**
 * Appends the {@code readFast} method of a generated decoder -- and its private
 * helpers -- to the decoder class body: a decoder that reads <em>canonical</em>
 * JSON straight from the document bytes.
 *
 * <p>
 * What the code is lives in {@link FastReaderTemplate}, in {@code buff-json}:
 * the same description is lowered to bytecode at run time for messages that
 * were generated without this plugin, so both routes produce the same reader.
 * This class only tells the template how the classes around a message are named
 * and prints the result as Java source.
 */
final class FastDecoderGenerator {

	private FastDecoderGenerator() {
	}

	/**
	 * Appends {@code readFast} and its private helpers to the decoder class body
	 * (nothing if the message is too wide to have one; the inherited
	 * {@code readFast} then bails and the general decoder handles every document).
	 */
	static void emit(StringBuilder sb, Descriptor msgDesc, String javaPackage, String decoderSimpleName,
			Map<String, String> protoToJavaClass, Map<String, String> protoToDecoderClass) {
		Env env = new Env(msgDesc, Ty.obj(javaPackage, decoderSimpleName), protoToJavaClass, protoToDecoderClass);
		Members members = FastReaderTemplate.generate(msgDesc, env, false);
		if (members == null) {
			return;
		}
		sb.append('\n');
		SourcePrinter.print(sb, members, "    ");
	}

	/** The names of the classes around one message, as the plugin knows them. */
	private static final class Env implements FastReaderTemplate.Env {
		private final Descriptor message;
		private final Ty.Obj self;
		private final Map<String, String> protoToJavaClass;
		private final Map<String, String> protoToDecoderClass;

		Env(Descriptor message, Ty.Obj self, Map<String, String> protoToJavaClass,
				Map<String, String> protoToDecoderClass) {
			this.message = message;
			this.self = self;
			this.protoToJavaClass = protoToJavaClass;
			this.protoToDecoderClass = protoToDecoderClass;
		}

		@Override
		public Ty.Obj message() {
			return javaClass(message);
		}

		@Override
		public Ty.Obj builder() {
			return Ty.obj(message().binaryName() + "$Builder");
		}

		@Override
		public Ty.Obj self() {
			return self;
		}

		@Override
		public Ty.Obj valueClass(FieldDescriptor field) {
			return javaClass(messageTypeOf(field));
		}

		@Override
		public Ty.Obj readerOf(FieldDescriptor field) {
			String decoder = protoToDecoderClass.get(messageTypeOf(field).getFullName());
			return decoder == null ? null : Ty.obj(decoder);
		}

		/** The message type a field holds; for a map, the type of its values. */
		private static Descriptor messageTypeOf(FieldDescriptor field) {
			return field.isMapField()
					? field.getMessageType().findFieldByName("value").getMessageType()
					: field.getMessageType();
		}

		@Override
		public boolean dynamicReaders() {
			return true; // a nested message of a class this run does not generate is read when it is met
		}

		@Override
		public boolean builderHas(String name, List<Ty> parameters) {
			return true; // protoc generates the accessors of every field; javac checks the rest
		}

		/**
		 * The class protoc generates for a message: its dotted source name, with the
		 * nesting made explicit ({@code pkg.Outer.Inner} is {@code pkg.Outer$Inner}).
		 */
		private Ty.Obj javaClass(Descriptor type) {
			String source = protoToJavaClass.get(type.getFullName());
			String javaPackage = BuffJsonProtocPlugin.javaPackage(type.getFile());
			String nested = javaPackage.isEmpty() ? source : source.substring(javaPackage.length() + 1);
			return Ty.obj(javaPackage, nested.replace('.', '$'));
		}
	}
}
