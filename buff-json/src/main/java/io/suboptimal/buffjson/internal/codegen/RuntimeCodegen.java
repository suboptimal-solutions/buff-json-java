package io.suboptimal.buffjson.internal.codegen;

import static io.suboptimal.buffjson.internal.codegen.E.*;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.Message;

import io.suboptimal.buffjson.BuffJsonCodecHolder;
import io.suboptimal.buffjson.BuffJsonGeneratedDecoder;
import io.suboptimal.buffjson.internal.FastInput;
import io.suboptimal.buffjson.internal.ProtobufJavaNames;
import io.suboptimal.buffjson.internal.ProtobufMessageReader;

/**
 * Generates, at run time, the decoder the protoc plugin would have generated
 * for a message class that has none.
 *
 * <p>
 * The plugin prints {@link FastReaderTemplate} as Java source and javac
 * compiles it. Here the same template is lowered to bytecode by a
 * {@link ClassBackend} (on Java 24+, the Class-File API) and defined as a class
 * next to the message class -- same package, same loader -- so the generated
 * code links against the public API of the message and its builder exactly as
 * compiled code would. The result is a {@link BuffJsonGeneratedDecoder} whose
 * {@code readFast} is the canonical-input reader; {@code readMessage} defers to
 * the runtime decoder, which is also what {@code readFast} falls back to for
 * anything that is not canonical.
 *
 * <p>
 * Everything is optional. Where no backend is available (older runtimes, native
 * images), the message is not accessible to a lookup, the classes do not see
 * this library, or anything at all goes wrong, the message has no generated
 * decoder and the runtime decoder does the work as before. The failure is
 * recorded, never thrown into a decode.
 *
 * <p>
 * Nested messages are handled as a graph: a message whose field types are
 * generated together with it, so the calls between them are direct calls of
 * constant instances that the JIT inlines, like in compiled code. Nested
 * messages that have a plugin-generated decoder use that one.
 */
public final class RuntimeCodegen {

	private static final String BACKEND_CLASS = "io.suboptimal.buffjson.internal.codegen.cf.ClassFileBackend";

	/** {@code -Dbuffjson.codegen=false} turns run-time generation off. */
	private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("buffjson.codegen"));
	/**
	 * {@code -Dbuffjson.codegen.debug=true} reports why a message got no generated
	 * decoder.
	 */
	private static final boolean DEBUG = Boolean.getBoolean("buffjson.codegen.debug");
	/**
	 * {@code -Dbuffjson.codegen.dump=DIR} writes every generated class file to
	 * {@code DIR}.
	 */
	private static final String DUMP = System.getProperty("buffjson.codegen.dump");

	private static final ClassBackend BACKEND = loadBackend();

	private static final Object LOCK = new Object();
	private static final Object FAILED = new Object();

	private static final Ty.Obj DECODER = Ty.obj("io.suboptimal.buffjson.BuffJsonGeneratedDecoder");
	private static final Ty.Obj MESSAGE = Ty.obj("com.google.protobuf.Message");
	private static final Ty.Obj DESCRIPTOR = Ty.obj("com.google.protobuf.Descriptors$Descriptor");
	private static final Ty.Obj JSON_READER = Ty.obj("com.alibaba.fastjson2.JSONReader");
	private static final Ty.Obj MESSAGE_READER = Ty.obj("io.suboptimal.buffjson.internal.ProtobufMessageReader");

	private RuntimeCodegen() {
	}

	private static ClassBackend loadBackend() {
		if (!ENABLED || Runtime.version().feature() < 24) {
			return null;
		}
		try {
			return (ClassBackend) Class.forName(BACKEND_CLASS).getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException | LinkageError unavailable) {
			return null;
		}
	}

	/** Whether this runtime can generate classes at all. */
	public static boolean available() {
		return BACKEND != null;
	}

	/**
	 * The class-file backend of this runtime, or {@code null} where there is none.
	 */
	public static ClassBackend backend() {
		return BACKEND;
	}

	// --- the results ---

	/**
	 * What became of one message class: the decoder, {@link #FAILED}, or nothing
	 * yet.
	 */
	private static final class Slot {
		volatile Object value;
		volatile Throwable failure;
	}

	private static ClassValue<Slot> slots() {
		return new ClassValue<>() {
			@Override
			protected Slot computeValue(Class<?> type) {
				return new Slot();
			}
		};
	}

	/** Decoders whose nested messages may use the plugin's decoders. */
	private static final ClassValue<Slot> MIXED = slots();
	/**
	 * Decoders generated end to end, ignoring the plugin's (for tests and
	 * comparisons).
	 */
	private static final ClassValue<Slot> ONLY = slots();

	/**
	 * The generated decoder of {@code messageClass}, or {@code null} if it has none
	 * (not available, not a generated message class, or generation failed).
	 *
	 * @param useCompiled
	 *            whether nested messages that have a plugin-generated decoder call
	 *            that one (otherwise every message of the graph is generated here)
	 */
	@SuppressWarnings("unchecked")
	public static BuffJsonGeneratedDecoder<Message> fastDecoder(Class<?> messageClass, boolean useCompiled) {
		if (BACKEND == null) {
			return null;
		}
		Slot slot = (useCompiled ? MIXED : ONLY).get(messageClass);
		Object value = slot.value;
		if (value == null) {
			value = generate(messageClass, useCompiled, slot);
		}
		return value == FAILED ? null : (BuffJsonGeneratedDecoder<Message>) value;
	}

	/**
	 * Why {@code messageClass} has no generated decoder; {@code null} if it has one
	 * or was not tried.
	 */
	public static Throwable failure(Class<?> messageClass, boolean useCompiled) {
		return (useCompiled ? MIXED : ONLY).get(messageClass).failure;
	}

	private static Object generate(Class<?> root, boolean useCompiled, Slot slot) {
		synchronized (LOCK) {
			if (slot.value != null) {
				return slot.value;
			}
			Plan plan = new Plan(useCompiled);
			try {
				if (plan.require(root) == null) {
					// not something classes can be generated for; do not ask again
					slot.failure = new IllegalStateException(root.getName() + " is not a public generated message"
							+ " class that generated code can be defined next to");
					slot.value = FAILED;
					if (DEBUG) {
						System.err.println("buff-json: no generated decoder: " + slot.failure.getMessage());
					}
					return FAILED;
				}
				plan.generateAll();
				plan.defineAll();
			} catch (Exception | LinkageError failure) {
				plan.failAll(failure);
			}
			return slot.value;
		}
	}

	// --- planning the graph ---

	private static final class Entry {
		final Class<?> messageClass;
		final Slot slot;
		final Ty.Obj name;
		byte[] bytes;
		Class<?> defined;

		Entry(Class<?> messageClass, Slot slot, Ty.Obj name) {
			this.messageClass = messageClass;
			this.slot = slot;
			this.name = name;
		}
	}

	private static final class Plan {
		final boolean useCompiled;
		final ClassValue<Slot> slots;
		final Map<Class<?>, Entry> entries = new LinkedHashMap<>();
		final ArrayDeque<Entry> pending = new ArrayDeque<>();

		Plan(boolean useCompiled) {
			this.useCompiled = useCompiled;
			this.slots = useCompiled ? MIXED : ONLY;
		}

		String suffix() {
			return useCompiled ? "$$BuffJsonFast" : "$$BuffJsonFastRt";
		}

		/**
		 * The generated class for {@code messageClass}: one already defined, or one
		 * this plan will generate. {@code null} if the message cannot have one.
		 */
		Ty.Obj require(Class<?> messageClass) {
			Entry existing = entries.get(messageClass);
			if (existing != null) {
				return existing.name;
			}
			Slot slot = slots.get(messageClass);
			Object value = slot.value;
			if (value == FAILED) {
				return null;
			}
			if (value != null) {
				return Ty.obj(value.getClass().getName());
			}
			if (!eligible(messageClass)) {
				return null;
			}
			Entry entry = new Entry(messageClass, slot, Ty.obj(messageClass.getName() + suffix()));
			entries.put(messageClass, entry);
			pending.add(entry);
			return entry.name;
		}

		void generateAll() throws ReflectiveOperationException {
			while (!pending.isEmpty()) {
				Entry entry = pending.poll();
				long start = System.nanoTime();
				entry.bytes = generate(entry);
				if (DEBUG) {
					System.err.printf("buff-json: generated %s (%d bytes) in %.1f ms%n", entry.name.binaryName(),
							entry.bytes.length, (System.nanoTime() - start) / 1e6);
				}
			}
		}

		private byte[] generate(Entry entry) throws ReflectiveOperationException {
			Class<?> messageClass = entry.messageClass;
			Message defaultInstance = ProtobufMessageReader.getDefaultInstance(messageClass);
			Descriptor descriptor = defaultInstance.getDescriptorForType();
			Class<?> builderClass = messageClass.getMethod("newBuilder").getReturnType();
			Env env = new Env(this, entry, builderClass);
			long start = System.nanoTime();
			Members reader = FastReaderTemplate.generate(descriptor, env, true);
			if (reader == null) {
				throw new IllegalStateException(messageClass.getName() + " has too many fields for a fast reader");
			}
			if (DEBUG) {
				System.err.printf("buff-json:   template %.1f ms%n", (System.nanoTime() - start) / 1e6);
			}
			Ty.Obj self = entry.name;
			Ty.Obj message = env.message();

			List<Members.FieldDef> fields = new ArrayList<>();
			fields.add(new Members.FieldDef(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL, self, "INSTANCE",
					newObj(self)));
			fields.addAll(reader.fields());
			List<Members.MethodDef> methods = new ArrayList<>(reader.methods());
			methods.add(readMessage(message));

			ClassDef definition = new ClassDef(self, List.of(DECODER), new Members(fields, methods));
			long lowering = System.nanoTime();
			byte[] bytes = BACKEND.lower(definition, messageClass.getClassLoader());
			if (DEBUG) {
				System.err.printf("buff-json:   lowering %.1f ms%n", (System.nanoTime() - lowering) / 1e6);
			}
			if (DUMP != null) {
				dump(self, bytes);
			}
			return bytes;
		}

		/**
		 * The general reader of a message without plugin-generated code is the runtime
		 * decoder, which is what this decoder's {@code readFast} falls back to anyway.
		 */
		private static Members.MethodDef readMessage(Ty.Obj message) {
			Var reader = new Var("reader", JSON_READER);
			Var messageReader = new Var("msgReader", MESSAGE_READER);
			Block body = new Block();
			body.ret(cast(message, call(messageReader, MESSAGE_READER, "readMessage", MESSAGE,
					List.of(JSON_READER, DESCRIPTOR, MESSAGE), reader, callStatic(message, "getDescriptor", DESCRIPTOR),
					callStatic(message, "getDefaultInstance", message))));
			return new Members.MethodDef(Modifier.PUBLIC, message, "readMessage", List.of(reader, messageReader),
					body.statements(), true, MESSAGE);
		}

		void defineAll() throws ReflectiveOperationException {
			long start = System.nanoTime();
			for (Entry entry : entries.values()) {
				MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(entry.messageClass, MethodHandles.lookup());
				entry.defined = lookup.defineClass(entry.bytes);
			}
			if (DEBUG) {
				System.err.printf("buff-json:   defined %d class(es) in %.1f ms%n", entries.size(),
						(System.nanoTime() - start) / 1e6);
				start = System.nanoTime();
			}
			// Initialising a class links it, which is when the JVM verifies the bytecode.
			Map<Entry, Object> instances = new LinkedHashMap<>();
			for (Entry entry : entries.values()) {
				instances.put(entry, entry.defined.getField("INSTANCE").get(null));
			}
			if (DEBUG) {
				System.err.printf("buff-json:   linked and initialised in %.1f ms%n",
						(System.nanoTime() - start) / 1e6);
			}
			for (var instance : instances.entrySet()) {
				instance.getKey().slot.value = instance.getValue();
			}
		}

		void failAll(Throwable failure) {
			if (DEBUG) {
				System.err.println("buff-json: no generated decoder: " + failure);
				failure.printStackTrace();
			}
			for (Entry entry : entries.values()) {
				entry.slot.failure = failure;
				entry.slot.value = FAILED;
			}
		}

		/**
		 * Whether generated code can be defined next to {@code messageClass} and see
		 * what it needs.
		 */
		private boolean eligible(Class<?> messageClass) {
			if (!GeneratedMessage.class.isAssignableFrom(messageClass)
					|| !Modifier.isPublic(messageClass.getModifiers())) {
				return false;
			}
			ClassLoader loader = messageClass.getClassLoader();
			if (loader == null) {
				return false;
			}
			try {
				Class<?> builder = messageClass.getMethod("newBuilder").getReturnType();
				Method build = builder.getMethod("build");
				if (!Modifier.isPublic(builder.getModifiers()) || build.getReturnType() != messageClass) {
					return false;
				}
				Descriptor descriptor = ProtobufMessageReader.getDefaultInstance(messageClass).getDescriptorForType();
				if (descriptor.getFields().size() > FastReaderTemplate.MAX_FIELDS) {
					return false;
				}
			} catch (ReflectiveOperationException | RuntimeException notUsable) {
				return false;
			}
			return sees(loader, FastInput.class) && sees(loader, BuffJsonGeneratedDecoder.class)
					&& sees(loader, ProtobufMessageReader.class) && sees(loader, com.alibaba.fastjson2.JSONReader.class)
					&& sees(loader, Message.class);
		}
	}

	/**
	 * Whether {@code loader} resolves {@code type}'s name to {@code type} itself.
	 */
	private static boolean sees(ClassLoader loader, Class<?> type) {
		try {
			return Class.forName(type.getName(), false, loader) == type;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	private static void dump(Ty.Obj name, byte[] bytes) {
		try {
			Path file = Path.of(DUMP, name.internalName() + ".class");
			Files.createDirectories(file.getParent());
			Files.write(file, bytes);
		} catch (IOException | RuntimeException e) {
			System.err.println("buff-json: cannot dump " + name.binaryName() + ": " + e);
		}
	}

	// --- what the template needs to know about a message class, read from the
	// class itself ---

	private static final class Env implements FastReaderTemplate.Env {
		private final Plan plan;
		private final Entry entry;
		private final Class<?> builderClass;

		Env(Plan plan, Entry entry, Class<?> builderClass) {
			this.plan = plan;
			this.entry = entry;
			this.builderClass = builderClass;
		}

		@Override
		public Ty.Obj message() {
			return (Ty.Obj) Ty.of(entry.messageClass);
		}

		@Override
		public Ty.Obj builder() {
			return (Ty.Obj) Ty.of(builderClass);
		}

		@Override
		public Ty.Obj self() {
			return entry.name;
		}

		@Override
		public Ty.Obj valueClass(FieldDescriptor field) {
			try {
				return (Ty.Obj) Ty.of(ProtobufJavaNames.messageClassOf(entry.messageClass, field));
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public Ty.Obj readerOf(FieldDescriptor field) {
			Class<?> child;
			try {
				child = ProtobufJavaNames.messageClassOf(entry.messageClass, field);
			} catch (ReflectiveOperationException e) {
				return null;
			}
			if (!GeneratedMessage.class.isAssignableFrom(child)
					|| entry.messageClass.getClassLoader() != child.getClassLoader()) {
				return null; // the generated classes must be able to see each other by name
			}
			Descriptor expected = field.isMapField()
					? field.getMessageType().findFieldByName("value").getMessageType()
					: field.getMessageType();
			if (ProtobufMessageReader.getDefaultInstance(child).getDescriptorForType() != expected) {
				return null;
			}
			if (plan.useCompiled
					&& ProtobufMessageReader.getDefaultInstance(child) instanceof BuffJsonCodecHolder holder) {
				return compiledReader(holder, child);
			}
			return plan.require(child);
		}

		/**
		 * The plugin's decoder for {@code child}, if it has the covariant
		 * {@code readFast} this code calls; an older plugin's decoder has not.
		 */
		private Ty.Obj compiledReader(BuffJsonCodecHolder holder, Class<?> child) {
			BuffJsonGeneratedDecoder<?> decoder = holder.buffJsonDecoder();
			if (decoder == null) {
				return null;
			}
			Class<?> type = decoder.getClass();
			try {
				if (!Modifier.isPublic(type.getModifiers())
						|| !Modifier.isPublic(type.getField("INSTANCE").getModifiers())
						|| type.getMethod("readFast", FastInput.class).getReturnType() != child
						|| !sees(entry.messageClass.getClassLoader(), type)) {
					return null;
				}
			} catch (ReflectiveOperationException e) {
				return null;
			}
			return (Ty.Obj) Ty.of(type);
		}

		@Override
		public boolean builderHas(String name, List<Ty> parameters) {
			try {
				Class<?>[] types = new Class<?>[parameters.size()];
				for (int i = 0; i < types.length; i++) {
					types[i] = classOf(parameters.get(i));
				}
				Method method = builderClass.getMethod(name, types);
				return method.getReturnType() == builderClass && !Modifier.isStatic(method.getModifiers());
			} catch (ReflectiveOperationException e) {
				return false;
			}
		}

		private Class<?> classOf(Ty type) throws ClassNotFoundException {
			return switch (type) {
				case Ty.Prim p -> switch (p) {
					case BOOLEAN -> boolean.class;
					case BYTE -> byte.class;
					case CHAR -> char.class;
					case SHORT -> short.class;
					case INT -> int.class;
					case LONG -> long.class;
					case FLOAT -> float.class;
					case DOUBLE -> double.class;
					case VOID -> void.class;
				};
				case Ty.Obj o -> Class.forName(o.binaryName(), false, entry.messageClass.getClassLoader());
				case Ty.Arr a -> classOf(a.elem()).arrayType();
			};
		}
	}
}
