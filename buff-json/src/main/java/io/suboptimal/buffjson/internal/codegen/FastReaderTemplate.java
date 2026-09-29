package io.suboptimal.buffjson.internal.codegen;

import static io.suboptimal.buffjson.internal.codegen.E.*;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

import io.suboptimal.buffjson.internal.FieldNameMatcher;
import io.suboptimal.buffjson.internal.ProtobufJavaNames;

/**
 * The {@code readFast} reader of one message type: a decoder that reads
 * <em>canonical</em> JSON straight from the document bytes.
 *
 * <p>
 * This is the single description of that reader. The protoc plugin prints it as
 * Java source into the decoder classes it generates ({@link SourcePrinter}); at
 * run time, for messages generated without the plugin, the same description is
 * lowered to bytecode. Both produce the same code, so both behave the same.
 *
 * <p>
 * The contract is the one stated on {@code BuffJsonGeneratedDecoder.readFast}:
 * anything but plain canonical input makes the generated code throw
 * {@code FastInput.bail()} (or run off the end of the array, which the caller
 * treats the same way) and the caller re-runs the document through the general
 * decoder. So this template never decides what a questionable input
 * <em>means</em>; the code it describes only has to be right for the input it
 * accepts.
 *
 * <h2>Shape of the code</h2>
 *
 * The position is a local {@code int p} and the document a local
 * {@code byte[] b}; tokens are recognised by a few inline comparisons and tiny
 * static helpers of {@code FastInput} that take a position and return the next
 * one. Keeping the position in a register (instead of a field that every token
 * method reloads and stores) is what makes this several times cheaper than
 * driving a cursor object token by token. The cursor object is only used
 * (handing the position over with {@code pos()}) for the less common values:
 * 64-bit integers, floating point, bytes, Timestamp/Duration, escaped or
 * non-ASCII strings.
 *
 * <p>
 * One small static method per field ({@code f0}, {@code f1}, ...) reads that
 * field's value, so no generated method approaches the JIT's limit for huge
 * methods however wide the message is. Field kinds without a fast reader
 * ({@code Any}, {@code Struct}, {@code Value}, {@code ListValue},
 * {@code FieldMask}) bail when the member is present.
 */
public final class FastReaderTemplate {

	/**
	 * Messages with more members than this get no fast reader (the switch that
	 * dispatches on the member would risk the JIT's huge-method limit).
	 */
	public static final int MAX_FIELDS = 800;

	/**
	 * From this many members on, the name matcher is split into 8 methods by a hash
	 * of the first four bytes.
	 */
	private static final int SPLIT_MATCHER_FIELDS = 64;

	/**
	 * What the template has to know about the message and the classes around it.
	 */
	public interface Env {
		/** The message class. */
		Ty.Obj message();

		/** Its builder class. */
		Ty.Obj builder();

		/**
		 * The class the members are generated into (their static helpers are called on
		 * it).
		 */
		Ty.Obj self();

		/**
		 * The Java class of the messages that {@code field} of the message holds: the
		 * value of a singular field, the elements of a repeated one, or the values of a
		 * map.
		 */
		Ty.Obj valueClass(FieldDescriptor field);

		/**
		 * A class with a public static {@code INSTANCE} whose
		 * {@code readFast(FastInput)} returns {@link #valueClass}, that reads messages
		 * of the type {@code field} holds -- or {@code null} if there is none, in which
		 * case the field bails.
		 */
		Ty.Obj readerOf(FieldDescriptor field);

		/** Whether the builder has a public method {@code name(parameters)}. */
		boolean builderHas(String name, List<Ty> parameters);

		/**
		 * Whether a nested message with no {@link #readerOf reader} known when the code
		 * is generated is read through {@code RuntimeReaders}, which looks one up when
		 * the message is decoded (a plugin-generated decoder of another module, or one
		 * generated at run time) and bails if there is none. Otherwise such a field
		 * bails.
		 */
		default boolean dynamicReaders() {
			return false;
		}
	}

	private static final Ty.Obj FI = Ty.obj("io.suboptimal.buffjson.internal.FastInput");
	private static final Ty.Obj RUNTIME_READERS = Ty.obj("io.suboptimal.buffjson.internal.codegen.RuntimeReaders");
	private static final Ty.Obj CLASS = Ty.obj("java.lang.Class");
	private static final Ty.Obj RUNTIME_EXCEPTION = Ty.obj("java.lang.RuntimeException");
	private static final Ty.Obj MESSAGE = Ty.obj("com.google.protobuf.Message");
	private static final Ty.Obj BYTE_STRING = Ty.obj("com.google.protobuf.ByteString");
	private static final Ty.Obj CHARSET = Ty.obj("java.nio.charset.Charset");
	private static final Ty.Obj CHARSETS = Ty.obj("java.nio.charset.StandardCharsets");
	private static final Ty.Obj ARRAYS = Ty.obj("java.util.Arrays");
	private static final Ty.Obj TIMESTAMP = Ty.obj("com.google.protobuf.Timestamp");
	private static final Ty.Obj DURATION = Ty.obj("com.google.protobuf.Duration");
	private static final Ty.Obj EMPTY = Ty.obj("com.google.protobuf.Empty");
	private static final Ty.Obj VALUE = Ty.obj("com.google.protobuf.Value");
	private static final Ty.Obj VALUE_BUILDER = Ty.obj("com.google.protobuf.Value$Builder");
	private static final Ty.Obj NULL_VALUE = Ty.obj("com.google.protobuf.NullValue");

	private static final int PRIVATE_STATIC = Modifier.PRIVATE | Modifier.STATIC;

	private FastReaderTemplate() {
	}

	/**
	 * A null JSON value normally means "absent" (skip), but for
	 * {@code google.protobuf.Value} and {@code google.protobuf.NullValue} fields it
	 * is meaningful. When the message has such fields it can't blanket-skip nulls;
	 * each field decides instead.
	 */
	public static boolean nullSensitive(Descriptor message) {
		return message.getFields().stream().anyMatch(fd -> isValue(fd) || isNullValue(fd));
	}

	/**
	 * The members of the reader, or {@code null} if the message is too wide for
	 * one.
	 *
	 * @param withSlowOrdinal
	 *            whether to include {@link #slowOrdinal}; the protoc plugin's
	 *            decoders have it already, for their general reader
	 */
	public static Members generate(Descriptor message, Env env, boolean withSlowOrdinal) {
		List<FieldDescriptor> fields = message.getFields();
		if (fields.size() > MAX_FIELDS) {
			return null; // the inherited readFast bails; the general decoder handles every document
		}
		List<Members.FieldDef> constants = new ArrayList<>();
		List<Members.MethodDef> methods = new ArrayList<>();
		methods.add(shell(env, fields, nullSensitive(message)));
		nameMatcher(env, fields, methods);
		if (withSlowOrdinal) {
			methods.add(slowOrdinal(fields));
		}
		Map<String, EnumDescriptor> enums = new LinkedHashMap<>();
		for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
			methods.add(fieldMethod(env, fields.get(ordinal), ordinal, enums));
		}
		enumHelpers(env, enums, constants, methods);
		return new Members(constants, methods);
	}

	// --- the current position ---

	/**
	 * The cursor, the document and the position: the three things every reader
	 * touches.
	 */
	private record Cur(Var c, Var b, Var p) {
		static Cur params() {
			return new Cur(new Var("c", FI), new Var("b", Ty.BYTES), new Var("p", Ty.INT));
		}
	}

	private static Expr bail() {
		return callStatic(FI, "bail", RUNTIME_EXCEPTION);
	}

	private static Expr fi(String method, Ty ret, Expr... args) {
		return callStatic(FI, method, ret, args);
	}

	/** {@code if (b[p] <= ' ') p = FastInput.ws(b, p);} */
	private static void skipWs(Block o, Cur k) {
		o.when(le(at(k.b, k.p), chr(' ')), t -> t.assign(k.p, fi("ws", Ty.INT, k.b, k.p)));
	}

	/** {@code p++} */
	private static void next(Block o, Cur k) {
		o.assign(k.p, add(k.p, 1));
	}

	/** {@code if (b[p] != expected) throw bail;} */
	private static void expect(Block o, Cur k, char expected) {
		o.when(ne(at(k.b, k.p), chr(expected)), t -> t.thr(bail()));
	}

	/** {@code c.pos(p)} */
	private static Expr setPos(Cur k) {
		return call(k.c, FI, "pos", Ty.VOID, k.p);
	}

	/** {@code c.pos()} */
	private static Expr getPos(Cur k) {
		return call(k.c, FI, "pos", Ty.INT);
	}

	// --- the message shell ---

	private static Members.MethodDef shell(Env env, List<FieldDescriptor> fields, boolean nullSensitive) {
		Var c = new Var("c", FI);
		Block o = new Block();
		Var b = o.decl(Ty.BYTES, "b", call(c, FI, "array", Ty.BYTES));
		Var p = o.decl(Ty.INT, "p", call(c, FI, "pos", Ty.INT));
		Cur k = new Cur(c, b, p);
		o.exec(call(c, FI, "enter", Ty.VOID));
		Var builder = o.decl(env.builder(), "builder", callStatic(env.message(), "newBuilder", env.builder()));
		expect(o, k, '{');
		next(o, k);
		skipWs(o, k);
		o.when(eq(at(b, p), chr('}')), t -> next(t, k), e -> e.loop(w -> {
			Var r = w.decl(Ty.INT, "r", callStatic(env.self(), "fastOrdinal", Ty.INT, b, p));
			Var f = w.decl(Ty.INT, "f");
			w.when(ge(r, 0), t -> {
				t.assign(f, shr(r, 6));
				t.assign(p, add(p, and(r, 63)));
			}, x -> {
				x.exec(setPos(k));
				x.assign(f, callStatic(env.self(), "slowOrdinal", Ty.INT, call(c, FI, "nameString", Ty.STRING)));
				x.assign(p, getPos(k));
			});
			skipWs(w, k);
			w.when(eq(at(b, p), chr('n')), t -> {
				t.exec(setPos(k));
				t.when(not(call(c, FI, "nullLiteral", Ty.BOOLEAN)), x -> x.thr(bail()));
				t.assign(p, getPos(k));
				if (nullSensitive) {
					nullCases(env, t, f, builder, fields);
				}
			}, e2 -> e2.switchOn(f, sw -> {
				for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
					int field = ordinal;
					sw.on(ordinal, x -> x.assign(p, callStatic(env.self(), "f" + field, Ty.INT, c, b, p, builder)));
				}
				sw.otherwise(x -> {
					x.exec(setPos(k));
					x.exec(call(c, FI, "skipValue", Ty.VOID));
					x.assign(p, getPos(k));
				});
			}));
			skipWs(w, k);
			Var ch = w.decl(Ty.BYTE, "ch", at(b, p));
			w.when(eq(ch, chr(',')), t -> {
				next(t, k);
				skipWs(t, k);
			}, e2 -> e2.when(eq(ch, chr('}')), t -> {
				next(t, k);
				t.brk();
			}, e3 -> e3.thr(bail())));
		}));
		o.exec(setPos(k));
		o.exec(call(c, FI, "leave", Ty.VOID));
		o.ret(call(builder, env.builder(), "build", env.message()));
		return new Members.MethodDef(Modifier.PUBLIC, env.message(), "readFast", List.of(c), o.statements(), true,
				MESSAGE);
	}

	/**
	 * A JSON null for a singular Value member is {@code Value{null_value}} and for
	 * a NullValue member {@code NULL_VALUE}; for every other member it means
	 * "absent", which needs no code.
	 */
	private static void nullCases(Env env, Block o, Var f, Var builder, List<FieldDescriptor> fields) {
		o.switchOn(f, sw -> {
			for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
				FieldDescriptor fd = fields.get(ordinal);
				if (fd.isRepeated()) {
					continue;
				}
				String setter = "set" + ProtobufJavaNames.accessorSuffix(fd.getName());
				if (isValue(fd) && env.builderHas(setter, List.of(VALUE))) {
					sw.on(ordinal,
							x -> x.exec(call(builder, env.builder(), setter, env.builder(), nullValueMessage())));
				} else if (isNullValue(fd) && env.builderHas(setter + "Value", List.of(Ty.INT))) {
					sw.on(ordinal, x -> x.exec(call(builder, env.builder(), setter + "Value", env.builder(), i(0))));
				}
			}
			sw.otherwise(x -> {
			});
		});
	}

	/** {@code Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()} */
	private static Expr nullValueMessage() {
		Expr builder = call(callStatic(VALUE, "newBuilder", VALUE_BUILDER), VALUE_BUILDER, "setNullValue",
				VALUE_BUILDER, getStatic(NULL_VALUE, "NULL_VALUE", NULL_VALUE));
		return call(builder, VALUE_BUILDER, "build", VALUE);
	}

	// --- member name matching ---

	/**
	 * A name that {@code fastOrdinal} can recognise byte-exactly, and the member it
	 * stands for.
	 */
	private record Candidate(FieldNameMatcher matcher, int ordinal) {
	}

	/**
	 * {@code fastOrdinal(b, p)}: for the common spelling (exactly
	 * {@code "jsonName":}) returns {@code ordinal << 6 | bytesConsumed}, otherwise
	 * -1 and the shell falls back to reading the name as a string (aliases,
	 * escapes, whitespace before the colon, unknown members).
	 */
	private static void nameMatcher(Env env, List<FieldDescriptor> fields, List<Members.MethodDef> methods) {
		Map<Integer, List<Candidate>> byPrefix = new LinkedHashMap<>();
		for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
			FieldDescriptor fd = fields.get(ordinal);
			addCandidate(byPrefix, fd.getJsonName(), ordinal);
			if (!fd.getName().equals(fd.getJsonName())) {
				// the proto field name is accepted too (what producers configured to keep proto
				// names write); slowOrdinal maps it to the same field
				addCandidate(byPrefix, fd.getName(), ordinal);
			}
		}
		Var b = new Var("b", Ty.BYTES);
		Var p = new Var("p", Ty.INT);
		if (byPrefix.isEmpty()) {
			Block o = new Block();
			o.ret(i(-1));
			methods.add(privateStatic(Ty.INT, "fastOrdinal", List.of(b, p), o));
			return;
		}
		if (fields.size() < SPLIT_MATCHER_FIELDS) {
			Block o = new Block();
			prefixSwitch(o, byPrefix, b, p, fi("i4", Ty.INT, b, p));
			o.ret(i(-1));
			methods.add(privateStatic(Ty.INT, "fastOrdinal", List.of(b, p), o));
			return;
		}
		// wide message: 8 methods, chosen by a hash of the prefix
		List<Map<Integer, List<Candidate>>> buckets = new ArrayList<>();
		for (int n = 0; n < 8; n++) {
			buckets.add(new LinkedHashMap<>());
		}
		for (var entry : byPrefix.entrySet()) {
			buckets.get(bucket(entry.getKey())).put(entry.getKey(), entry.getValue());
		}
		Block o = new Block();
		Var w = o.decl(Ty.INT, "w", fi("i4", Ty.INT, b, p));
		o.switchOn(ushr(mul(w, hex(0x9E3779B1)), 29), sw -> {
			for (int n = 0; n < 8; n++) {
				if (!buckets.get(n).isEmpty()) {
					int bucket = n;
					sw.on(n, x -> x.ret(callStatic(env.self(), "fastOrdinal" + bucket, Ty.INT, b, p, w)));
				}
			}
			sw.otherwise(x -> x.ret(i(-1)));
		});
		methods.add(privateStatic(Ty.INT, "fastOrdinal", List.of(b, p), o));
		for (int n = 0; n < 8; n++) {
			if (buckets.get(n).isEmpty()) {
				continue;
			}
			Var bn = new Var("b", Ty.BYTES);
			Var pn = new Var("p", Ty.INT);
			Var wn = new Var("w", Ty.INT);
			Block m = new Block();
			prefixSwitch(m, buckets.get(n), bn, pn, wn);
			m.ret(i(-1));
			methods.add(privateStatic(Ty.INT, "fastOrdinal" + n, List.of(bn, pn, wn), m));
		}
	}

	private static void addCandidate(Map<Integer, List<Candidate>> byPrefix, String name, int ordinal) {
		FieldNameMatcher matcher = FieldNameMatcher.ofInline(name);
		if (matcher == null) {
			return; // not matchable byte-exactly; the name is read as a string and slowOrdinal
					// resolves it
		}
		byPrefix.computeIfAbsent(matcher.prefix(), k -> new ArrayList<>()).add(new Candidate(matcher, ordinal));
	}

	private static int bucket(int prefix) {
		return (prefix * 0x9E3779B1) >>> 29;
	}

	/**
	 * {@code switch (selector) { case <prefix>: if (<rest matches>) return
	 * <result>; ... }} -- the tests of one prefix run in order, and a name that
	 * matches none falls out of the switch.
	 */
	private static void prefixSwitch(Block o, Map<Integer, List<Candidate>> byPrefix, Var b, Var p, Expr selector) {
		o.switchOn(selector, sw -> {
			for (var entry : byPrefix.entrySet()) {
				sw.on(entry.getKey(), x -> {
					for (Candidate candidate : entry.getValue()) {
						int result = (candidate.ordinal << 6) | candidate.matcher.consumed();
						x.when(restMatches(candidate.matcher, b, p), t -> t.ret(i(result)));
					}
				});
			}
		});
	}

	/**
	 * The name's bytes after the four already compared, as 8/4/1-byte reads that
	 * must all match.
	 */
	private static Expr restMatches(FieldNameMatcher matcher, Var b, Var p) {
		Expr test = null;
		for (FieldNameMatcher.Piece piece : matcher.inlinePieces()) {
			Expr where = add(p, piece.offset());
			Expr part = switch (piece.width()) {
				case 8 -> eq(fi("l8", Ty.LONG, b, where), hex(piece.value()));
				case 4 -> eq(fi("i4", Ty.INT, b, where), hex((int) piece.value()));
				default -> eq(at(b, where), i((int) piece.value()));
			};
			test = test == null ? part : land(test, part);
		}
		return test;
	}

	/**
	 * {@code slowOrdinal(name)}: the ordinal of the member called {@code name}
	 * (either spelling), or -2. A hash switch followed by {@code equals}, the way
	 * javac lowers a switch on strings.
	 */
	public static Members.MethodDef slowOrdinal(List<FieldDescriptor> fields) {
		Map<Integer, List<Map.Entry<String, Integer>>> byHash = new LinkedHashMap<>();
		for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
			FieldDescriptor fd = fields.get(ordinal);
			byHash.computeIfAbsent(fd.getJsonName().hashCode(), k -> new ArrayList<>())
					.add(Map.entry(fd.getJsonName(), ordinal));
			if (!fd.getName().equals(fd.getJsonName())) {
				byHash.computeIfAbsent(fd.getName().hashCode(), k -> new ArrayList<>())
						.add(Map.entry(fd.getName(), ordinal));
			}
		}
		Var name = new Var("name", Ty.STRING);
		Block o = new Block();
		if (!byHash.isEmpty()) {
			o.switchOn(call(name, Ty.STRING, "hashCode", Ty.INT), sw -> {
				for (var group : byHash.entrySet()) {
					sw.on(group.getKey(), x -> {
						for (var candidate : group.getValue()) {
							x.when(call(name, Ty.STRING, "equals", Ty.BOOLEAN, List.of(Ty.OBJECT),
									str(candidate.getKey())), t -> t.ret(i(candidate.getValue())));
						}
					});
				}
			});
		}
		o.ret(i(-2));
		return privateStatic(Ty.INT, "slowOrdinal", List.of(name), o);
	}

	private static Members.MethodDef privateStatic(Ty ret, String name, List<Var> params, Block body) {
		return new Members.MethodDef(PRIVATE_STATIC, ret, name, params, body.statements(), false, null);
	}

	// --- one method per field ---

	private static Members.MethodDef fieldMethod(Env env, FieldDescriptor fd, int ordinal,
			Map<String, EnumDescriptor> enums) {
		Cur k = Cur.params();
		Var builder = new Var("builder", env.builder());
		Block body = new Block();
		String name = ProtobufJavaNames.accessorSuffix(fd.getName());
		boolean supported;
		if (fd.isMapField()) {
			supported = mapBody(env, body, k, builder, fd, name, enums);
		} else if (fd.isRepeated()) {
			supported = repeatedBody(env, body, k, builder, fd, name, enums);
		} else {
			supported = singularBody(env, body, k, builder, fd, name, enums);
		}
		if (!supported) {
			body = new Block();
			body.thr(bail());
		}
		return privateStatic(Ty.INT, "f" + ordinal, List.of(k.c, k.b, k.p, builder), body);
	}

	private static boolean singularBody(Env env, Block o, Cur k, Var builder, FieldDescriptor fd, String name,
			Map<String, EnumDescriptor> enums) {
		Var v = value(env, o, k, fd, fd, "v", "", enums);
		if (v == null) {
			return false;
		}
		String setter = "set" + name + (isEnum(fd) ? "Value" : "");
		if (!env.builderHas(setter, List.of(v.type()))) {
			return false;
		}
		o.exec(call(builder, env.builder(), setter, env.builder(), v));
		o.ret(k.p);
		return true;
	}

	private static boolean repeatedBody(Env env, Block o, Cur k, Var builder, FieldDescriptor fd, String name,
			Map<String, EnumDescriptor> enums) {
		Block element = new Block();
		Var v = value(env, element, k, fd, fd, "v", "", enums);
		if (v == null) {
			return false;
		}
		String adder = "add" + name + (isEnum(fd) ? "Value" : "");
		if (!env.builderHas(adder, List.of(v.type()))) {
			return false;
		}
		expect(o, k, '[');
		next(o, k);
		skipWs(o, k);
		o.when(eq(at(k.b, k.p), chr(']')), t -> t.ret(add(k.p, 1)));
		o.loop(w -> {
			w.statements().addAll(element.statements());
			w.exec(call(builder, env.builder(), adder, env.builder(), v));
			separator(w, k, ']');
		});
		return true;
	}

	private static boolean mapBody(Env env, Block o, Cur k, Var builder, FieldDescriptor fd, String name,
			Map<String, EnumDescriptor> enums) {
		FieldDescriptor keyFd = fd.getMessageType().findFieldByName("key");
		FieldDescriptor valueFd = fd.getMessageType().findFieldByName("value");
		Block entry = new Block();
		Var key = key(entry, k, keyFd, "key");
		if (key == null) {
			return false;
		}
		skipWs(entry, k);
		expect(entry, k, ':');
		next(entry, k);
		skipWs(entry, k);
		Var v = value(env, entry, k, fd, valueFd, "v", "", enums);
		if (v == null) {
			return false;
		}
		String putter = "put" + name + (isEnum(valueFd) ? "Value" : "");
		if (!env.builderHas(putter, List.of(key.type(), v.type()))) {
			return false;
		}
		entry.exec(call(builder, env.builder(), putter, env.builder(), key, v));
		expect(o, k, '{');
		next(o, k);
		skipWs(o, k);
		o.when(eq(at(k.b, k.p), chr('}')), t -> t.ret(add(k.p, 1)));
		o.loop(w -> {
			w.statements().addAll(entry.statements());
			separator(w, k, '}');
		});
		return true;
	}

	/**
	 * After an element / entry: whitespace, then a comma (continue) or the closing
	 * bracket (return the position after it).
	 */
	private static void separator(Block o, Cur k, char closing) {
		skipWs(o, k);
		Var ch = o.decl(Ty.BYTE, "ch", at(k.b, k.p));
		o.when(eq(ch, chr(',')), t -> {
			next(t, k);
			skipWs(t, k);
		}, e -> e.when(eq(ch, chr(closing)), t -> t.ret(add(k.p, 1)), x -> x.thr(bail())));
	}

	// --- values ---

	private static boolean isEnum(FieldDescriptor fd) {
		return fd.getJavaType() == FieldDescriptor.JavaType.ENUM;
	}

	private static boolean isValue(FieldDescriptor fd) {
		return fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE
				&& "google.protobuf.Value".equals(fd.getMessageType().getFullName());
	}

	private static boolean isNullValue(FieldDescriptor fd) {
		return isEnum(fd) && "google.protobuf.NullValue".equals(fd.getEnumType().getFullName());
	}

	private static boolean unsigned32(FieldDescriptor fd) {
		return fd.getType() == FieldDescriptor.Type.UINT32 || fd.getType() == FieldDescriptor.Type.FIXED32;
	}

	private static boolean unsigned64(FieldDescriptor fd) {
		return fd.getType() == FieldDescriptor.Type.UINT64 || fd.getType() == FieldDescriptor.Type.FIXED64;
	}

	/**
	 * Appends statements that read one value of {@code fd} at {@code p}, declare a
	 * local named {@code name} for it and leave {@code p} just after it (not past
	 * trailing whitespace). Returns the local, or {@code null} -- having appended
	 * nothing usable -- if the kind has no fast reader. {@code carrier} is the
	 * field of the message being read that holds the value: {@code fd} itself, or
	 * for the value of a map the map field.
	 */
	private static Var value(Env env, Block o, Cur k, FieldDescriptor carrier, FieldDescriptor fd, String name,
			String suffix, Map<String, EnumDescriptor> enums) {
		return switch (fd.getJavaType()) {
			case INT -> packedInt(o, k, unsigned32(fd) ? "uint32At" : "int32At", name, suffix);
			case LONG -> handOver(o, k, Ty.LONG, name, unsigned64(fd) ? "uint64" : "int64");
			case FLOAT -> handOver(o, k, Ty.FLOAT, name, "float32");
			case DOUBLE -> handOver(o, k, Ty.DOUBLE, name, "float64");
			case BOOLEAN -> readBool(o, k, name);
			case STRING -> readString(o, k, name, suffix);
			case BYTE_STRING -> handOver(o, k, BYTE_STRING, name, "bytes");
			case ENUM -> {
				EnumDescriptor type = fd.getEnumType();
				enums.putIfAbsent(type.getFullName(), type);
				Var r = o.decl(Ty.LONG, "r" + suffix, callStatic(env.self(), enumHelperName(type), Ty.LONG, k.b, k.p));
				yield unpack(o, k, r, name);
			}
			case MESSAGE -> message(env, o, k, carrier, fd, name, suffix);
		};
	}

	/**
	 * {@code long r = FastInput.<method>(b, p); int name = (int) (r >> 32); p = (int) r;}
	 */
	private static Var packedInt(Block o, Cur k, String method, String name, String suffix) {
		Var r = o.decl(Ty.LONG, "r" + suffix, fi(method, Ty.LONG, k.b, k.p));
		return unpack(o, k, r, name);
	}

	private static Var unpack(Block o, Cur k, Var r, String name) {
		Var v = o.decl(Ty.INT, name, cast(Ty.INT, shr(r, 32)));
		o.assign(k.p, cast(Ty.INT, r));
		return v;
	}

	/** Runs a cursor method that reads at, and advances, the shared position. */
	private static Var handOver(Block o, Cur k, Ty type, String name, String method) {
		o.exec(setPos(k));
		Var v = o.decl(type, name, call(k.c, FI, method, type));
		o.assign(k.p, getPos(k));
		return v;
	}

	private static Var readBool(Block o, Cur k, String name) {
		Var v = o.decl(Ty.BOOLEAN, name);
		o.when(eq(fi("i4", Ty.INT, k.b, k.p), hex(0x65757274)), t -> {
			t.assign(v, E.bool(true));
			t.assign(k.p, add(k.p, 4));
		}, e -> e.when(land(eq(fi("i4", Ty.INT, k.b, k.p), hex(0x736c6166)), eq(at(k.b, add(k.p, 4)), chr('e'))), t -> {
			t.assign(v, E.bool(false));
			t.assign(k.p, add(k.p, 5));
		}, x -> x.thr(bail())));
		return v;
	}

	private static Var readString(Block o, Cur k, String name, String suffix) {
		expect(o, k, '"');
		Var e = o.decl(Ty.INT, "e" + suffix, fi("plainStringEnd", Ty.INT, k.b, add(k.p, 1)));
		Var v = o.decl(Ty.STRING, name);
		o.when(ge(e, 0), t -> {
			t.assign(v, newObj(Ty.STRING, k.b, add(k.p, 1), sub(sub(e, k.p), i(1)),
					getStatic(CHARSETS, "ISO_8859_1", CHARSET)));
			t.assign(k.p, add(e, 1));
		}, x -> {
			x.exec(setPos(k));
			x.assign(v, call(k.c, FI, "string", Ty.STRING));
			x.assign(k.p, getPos(k));
		});
		return v;
	}

	private static Var message(Env env, Block o, Cur k, FieldDescriptor carrier, FieldDescriptor fd, String name,
			String suffix) {
		Descriptor type = fd.getMessageType();
		switch (type.getFullName()) {
			case "google.protobuf.Timestamp" -> {
				return handOver(o, k, TIMESTAMP, name, "timestamp");
			}
			case "google.protobuf.Duration" -> {
				return handOver(o, k, DURATION, name, "duration");
			}
			case "google.protobuf.Empty" -> {
				expect(o, k, '{');
				o.exec(setPos(k));
				o.exec(call(k.c, FI, "skipValue", Ty.VOID));
				o.assign(k.p, getPos(k));
				return o.decl(EMPTY, name, callStatic(EMPTY, "getDefaultInstance", EMPTY));
			}
			case "google.protobuf.DoubleValue" -> {
				return wrapper(env, o, k, type, "DoubleValue", name, suffix);
			}
			case "google.protobuf.FloatValue" -> {
				return wrapper(env, o, k, type, "FloatValue", name, suffix);
			}
			case "google.protobuf.Int64Value" -> {
				return wrapper(env, o, k, type, "Int64Value", name, suffix);
			}
			case "google.protobuf.UInt64Value" -> {
				return wrapper(env, o, k, type, "UInt64Value", name, suffix);
			}
			case "google.protobuf.Int32Value" -> {
				return wrapper(env, o, k, type, "Int32Value", name, suffix);
			}
			case "google.protobuf.UInt32Value" -> {
				return wrapper(env, o, k, type, "UInt32Value", name, suffix);
			}
			case "google.protobuf.BoolValue" -> {
				return wrapper(env, o, k, type, "BoolValue", name, suffix);
			}
			case "google.protobuf.StringValue" -> {
				return wrapper(env, o, k, type, "StringValue", name, suffix);
			}
			case "google.protobuf.BytesValue" -> {
				return wrapper(env, o, k, type, "BytesValue", name, suffix);
			}
			case "google.protobuf.Any", "google.protobuf.FieldMask", "google.protobuf.Struct", "google.protobuf.Value",
					"google.protobuf.ListValue" -> {
				return null;
			}
			default -> {
				Ty.Obj reader = env.readerOf(carrier);
				Ty.Obj valueClass = env.valueClass(carrier);
				if (reader == null) {
					if (!env.dynamicReaders()) {
						return null;
					}
					o.exec(setPos(k));
					Var v = o.decl(valueClass, name, cast(valueClass, callStatic(RUNTIME_READERS, "readFast", MESSAGE,
							List.of(FI, CLASS), k.c, classLit(valueClass))));
					o.assign(k.p, getPos(k));
					return v;
				}
				o.exec(setPos(k));
				Var v = o.decl(valueClass, name,
						call(getStatic(reader, "INSTANCE", reader), reader, "readFast", valueClass, k.c));
				o.assign(k.p, getPos(k));
				return v;
			}
		}
	}

	/**
	 * A wrapper message is written as its bare value; the inner value is read like
	 * a field of that scalar type.
	 */
	private static Var wrapper(Env env, Block o, Cur k, Descriptor type, String simpleName, String name,
			String suffix) {
		Ty.Obj wrapperClass = Ty.obj("com.google.protobuf." + simpleName);
		FieldDescriptor valueField = type.findFieldByName("value");
		Var inner = value(env, o, k, valueField, valueField, name + "Inner", suffix + "w", new LinkedHashMap<>());
		return o.decl(wrapperClass, name, callStatic(wrapperClass, "of", wrapperClass, inner));
	}

	private static Var key(Block o, Cur k, FieldDescriptor keyFd, String name) {
		return switch (keyFd.getJavaType()) {
			case STRING -> readString(o, k, name, "k");
			case INT -> packedInt(o, k, unsigned32(keyFd) ? "uint32KeyAt" : "int32KeyAt", name, "k");
			case LONG -> handOver(o, k, Ty.LONG, name, unsigned64(keyFd) ? "uint64Key" : "int64Key");
			case BOOLEAN -> handOver(o, k, Ty.BOOLEAN, name, "boolKey");
			default -> null;
		};
	}

	// --- enums ---

	private static String enumHelperName(EnumDescriptor enumType) {
		return "enumFast_" + enumType.getFullName().replace('.', '_');
	}

	/**
	 * One helper per referenced enum type. A quoted name is matched against the
	 * value names by length and bytes (no {@code String} is created); a bare
	 * integer is read as-is, so unknown numbers survive as they do in the general
	 * decoder; an unknown name gives up so the general decoder reports it. Returns
	 * {@code number << 32 | positionAfter}.
	 */
	private static void enumHelpers(Env env, Map<String, EnumDescriptor> enums, List<Members.FieldDef> constants,
			List<Members.MethodDef> methods) {
		int index = 0;
		for (EnumDescriptor enumType : enums.values()) {
			int id = index++;
			Map<Integer, List<EnumValueDescriptor>> byLength = new LinkedHashMap<>();
			for (EnumValueDescriptor value : enumType.getValues()) {
				byLength.computeIfAbsent(value.getName().length(), k -> new ArrayList<>()).add(value);
			}
			Map<String, String> constantOf = new LinkedHashMap<>();
			int n = 0;
			for (EnumValueDescriptor value : enumType.getValues()) {
				String constant = "E" + id + "_" + n++;
				constantOf.put(value.getName(), constant);
				constants.add(new Members.FieldDef(PRIVATE_STATIC | Modifier.FINAL, Ty.BYTES, constant,
						call(str(value.getName()), Ty.STRING, "getBytes", Ty.BYTES,
								getStatic(CHARSETS, "US_ASCII", CHARSET))));
			}
			Var b = new Var("b", Ty.BYTES);
			Var p = new Var("p", Ty.INT);
			Block o = new Block();
			o.when(ne(at(b, p), chr('"')), t -> t.ret(fi("int32At", Ty.LONG, b, p)));
			Var s = o.decl(Ty.INT, "s", add(p, 1));
			Var e = o.decl(Ty.INT, "e", fi("plainStringEnd", Ty.INT, b, s));
			o.when(lt(e, 0), t -> t.thr(bail()));
			Var v = o.decl(Ty.INT, "v");
			o.switchOn(sub(e, s), sw -> {
				for (var group : byLength.entrySet()) {
					sw.on(group.getKey(), x -> {
						for (EnumValueDescriptor value : group.getValue()) {
							Expr constant = getStatic(env.self(), constantOf.get(value.getName()), Ty.BYTES);
							x.when(callStatic(ARRAYS, "equals", Ty.BOOLEAN, b, s, e, constant, i(0), i(group.getKey())),
									t -> {
										t.assign(v, i(value.getNumber()));
										t.brk();
									});
						}
						x.thr(bail());
					});
				}
				sw.otherwise(x -> x.thr(bail()));
			});
			o.ret(or(shl(cast(Ty.LONG, v), 32), add(e, 1)));
			methods.add(privateStatic(Ty.LONG, enumHelperName(enumType), List.of(b, p), o));
		}
	}
}
