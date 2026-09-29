package io.suboptimal.buffjson.protoc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

import io.suboptimal.buffjson.internal.FieldNameMatcher;

/**
 * Emits the {@code readFast} method of a generated decoder: a decoder that
 * reads <em>canonical</em> JSON straight from the document bytes.
 *
 * <p>
 * The contract is the one stated on {@code BuffJsonGeneratedDecoder.readFast}:
 * anything but plain canonical input makes the emitted code throw
 * {@code FastInput.bail()} (or run off the end of the array, which the caller
 * treats the same way) and the caller re-runs the document through
 * {@code readMessage}. So this generator never decides what a questionable
 * input <em>means</em>; the code it emits only has to be right for the input it
 * accepts.
 *
 * <h2>Shape of the emitted code</h2>
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
final class FastDecoderGenerator {

	private static final String FI = "io.suboptimal.buffjson.internal.FastInput";
	private static final String BAIL = "throw " + FI + ".bail();";

	/**
	 * Messages with more members than this get no fast reader (the switch that
	 * dispatches on the member would risk the JIT's huge-method limit).
	 */
	private static final int MAX_FIELDS = 800;

	/**
	 * From this many members on, the name matcher is split into 8 methods by a hash
	 * of the first four bytes.
	 */
	private static final int SPLIT_MATCHER_FIELDS = 64;

	private FastDecoderGenerator() {
	}

	/** A tiny indenting source writer. */
	private static final class Code {
		private final StringBuilder out;
		private String indent;

		Code(StringBuilder out, String indent) {
			this.out = out;
			this.indent = indent;
		}

		Code line(String text) {
			out.append(indent).append(text).append('\n');
			return this;
		}

		Code open(String text) {
			line(text);
			indent += "    ";
			return this;
		}

		Code close(String text) {
			indent = indent.substring(4);
			return line(text);
		}

		/**
		 * A line at the enclosing level that continues the current block ("} else {").
		 */
		Code mid(String text) {
			out.append(indent, 0, indent.length() - 4).append(text).append('\n');
			return this;
		}

		Code blank() {
			out.append('\n');
			return this;
		}
	}

	/**
	 * Appends {@code readFast} and its private helpers to the decoder class body.
	 */
	static void emit(StringBuilder sb, Descriptor msgDesc, String messageClassName,
			Map<String, String> protoToJavaClass, Map<String, String> protoToDecoderClass, boolean nullSensitive) {

		List<FieldDescriptor> fields = msgDesc.getFields();
		if (fields.size() > MAX_FIELDS) {
			return; // the inherited readFast bails; the general decoder handles every document
		}
		Code o = new Code(sb, "    ");
		o.blank();
		emitShell(o, messageClassName, fields, nullSensitive);
		emitNameMatcher(o, fields);
		Map<String, EnumDescriptor> enums = new LinkedHashMap<>();
		for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
			o.blank();
			emitFieldMethod(o, fields.get(ordinal), ordinal, messageClassName, protoToDecoderClass, enums);
		}
		emitEnumHelpers(o, enums);
	}

	// --- the message shell ---

	private static void emitShell(Code o, String messageClassName, List<FieldDescriptor> fields,
			boolean nullSensitive) {
		o.line("@Override");
		o.open("public " + messageClassName + " readFast(" + FI + " c) {");
		o.line("final byte[] b = c.array();");
		o.line("int p = c.pos();");
		o.line("c.enter();");
		o.line(messageClassName + ".Builder builder = " + messageClassName + ".newBuilder();");
		o.line("if (b[p] != '{') " + BAIL);
		o.line("p++;");
		o.line("if (b[p] <= ' ') p = " + FI + ".ws(b, p);");
		o.open("if (b[p] == '}') {");
		o.line("p++;");
		o.mid("} else {");
		o.open("while (true) {");
		o.line("int r = fastOrdinal(b, p);");
		o.line("int f;");
		o.open("if (r >= 0) {");
		o.line("f = r >> 6;");
		o.line("p += r & 63;");
		o.mid("} else {");
		o.line("c.pos(p);");
		o.line("f = slowOrdinal(c.nameString());");
		o.line("p = c.pos();");
		o.close("}");
		o.line("if (b[p] <= ' ') p = " + FI + ".ws(b, p);");
		o.open("if (b[p] == 'n') {");
		o.line("c.pos(p);");
		o.line("if (!c.nullLiteral()) " + BAIL);
		o.line("p = c.pos();");
		if (nullSensitive) {
			// null is a value for a singular Value / NullValue member and means "absent"
			// for every other one
			o.open("switch (f) {");
			for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
				FieldDescriptor fd = fields.get(ordinal);
				if (fd.isRepeated()) {
					continue;
				}
				String setter = "builder.set" + BuffJsonProtocPlugin.toCamelCase(fd.getName());
				if (isValue(fd)) {
					o.line("case " + ordinal + " -> " + setter
							+ "(com.google.protobuf.Value.newBuilder().setNullValue(com.google.protobuf.NullValue.NULL_VALUE).build());");
				} else if (isNullValue(fd)) {
					o.line("case " + ordinal + " -> " + setter + "Value(0);");
				}
			}
			o.line("default -> { }");
			o.close("}");
		}
		o.mid("} else {");
		o.open("switch (f) {");
		for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
			o.line("case " + ordinal + " -> p = f" + ordinal + "(c, b, p, builder);");
		}
		o.line("default -> {");
		o.line("    c.pos(p);");
		o.line("    c.skipValue();");
		o.line("    p = c.pos();");
		o.line("}");
		o.close("}");
		o.close("}");
		o.line("if (b[p] <= ' ') p = " + FI + ".ws(b, p);");
		o.line("byte ch = b[p];");
		o.open("if (ch == ',') {");
		o.line("p++;");
		o.line("if (b[p] <= ' ') p = " + FI + ".ws(b, p);");
		o.mid("} else if (ch == '}') {");
		o.line("p++;");
		o.line("break;");
		o.mid("} else {");
		o.line(BAIL);
		o.close("}");
		o.close("}"); // while
		o.close("}"); // else
		o.line("c.pos(p);");
		o.line("c.leave();");
		o.line("return builder.build();");
		o.close("}");
	}

	// --- member name matching ---

	/**
	 * {@code fastOrdinal(b, p)}: for the common spelling (exactly
	 * {@code "jsonName":}) returns {@code ordinal << 6 | bytesConsumed}, otherwise
	 * -1 and the shell falls back to reading the name as a string (aliases,
	 * escapes, whitespace before the colon, unknown members).
	 */
	private static void emitNameMatcher(Code o, List<FieldDescriptor> fields) {
		Map<Integer, List<String>> byPrefix = new LinkedHashMap<>();
		for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
			FieldDescriptor fd = fields.get(ordinal);
			addNameMatcher(byPrefix, fd.getJsonName(), ordinal);
			if (!fd.getName().equals(fd.getJsonName())) {
				// the proto field name is accepted too (what producers configured to keep proto
				// names write); slowOrdinal maps it to the same field
				addNameMatcher(byPrefix, fd.getName(), ordinal);
			}
		}
		o.blank();
		if (byPrefix.isEmpty()) {
			o.open("private static int fastOrdinal(byte[] b, int p) {");
			o.line("return -1;");
			o.close("}");
			return;
		}
		if (fields.size() < SPLIT_MATCHER_FIELDS) {
			o.open("private static int fastOrdinal(byte[] b, int p) {");
			emitPrefixSwitch(o, byPrefix, "b, p");
			o.line("return -1;");
			o.close("}");
			return;
		}
		// wide message: 8 methods, chosen by a hash of the prefix
		List<Map<Integer, List<String>>> buckets = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			buckets.add(new LinkedHashMap<>());
		}
		for (var entry : byPrefix.entrySet()) {
			buckets.get(bucket(entry.getKey())).put(entry.getKey(), entry.getValue());
		}
		o.open("private static int fastOrdinal(byte[] b, int p) {");
		o.line("int w = " + FI + ".i4(b, p);");
		o.open("switch ((w * 0x9E3779B1) >>> 29) {");
		for (int i = 0; i < 8; i++) {
			if (!buckets.get(i).isEmpty()) {
				o.line("case " + i + ": return fastOrdinal" + i + "(b, p, w);");
			}
		}
		o.line("default: return -1;");
		o.close("}");
		o.close("}");
		for (int i = 0; i < 8; i++) {
			if (buckets.get(i).isEmpty()) {
				continue;
			}
			o.blank();
			o.open("private static int fastOrdinal" + i + "(byte[] b, int p, int w) {");
			emitPrefixSwitch(o, buckets.get(i), "w");
			o.line("return -1;");
			o.close("}");
		}
	}

	private static void addNameMatcher(Map<Integer, List<String>> byPrefix, String name, int ordinal) {
		FieldNameMatcher matcher = FieldNameMatcher.ofInline(name);
		if (matcher == null) {
			return; // not matchable byte-exactly; the name is read as a string and slowOrdinal
					// resolves it
		}
		byPrefix.computeIfAbsent(matcher.prefix(), k -> new ArrayList<>())
				.add("if (" + matcher.inlineTest("b", "p") + ") return " + ((ordinal << 6) | matcher.consumed()) + ";");
	}

	private static int bucket(int prefix) {
		return (prefix * 0x9E3779B1) >>> 29;
	}

	private static void emitPrefixSwitch(Code o, Map<Integer, List<String>> byPrefix, String selector) {
		String expression = selector.equals("w") ? "w" : FI + ".i4(" + selector + ")";
		o.open("switch (" + expression + ") {");
		for (var entry : byPrefix.entrySet()) {
			o.line("case " + String.format("0x%08x", entry.getKey()) + ":");
			for (String statement : entry.getValue()) {
				o.line("    " + statement);
			}
			o.line("    break;");
		}
		o.line("default:");
		o.line("    break;");
		o.close("}");
	}

	// --- one method per field ---

	private static void emitFieldMethod(Code o, FieldDescriptor fd, int ordinal, String messageClassName,
			Map<String, String> protoToDecoderClass, Map<String, EnumDescriptor> enums) {
		o.open("private static int f" + ordinal + "(" + FI + " c, byte[] b, int p, " + messageClassName
				+ ".Builder builder) {");
		String name = BuffJsonProtocPlugin.toCamelCase(fd.getName());
		Code body = new Code(new StringBuilder(), o.indent);
		boolean supported;
		if (fd.isMapField()) {
			supported = mapBody(body, fd, name, protoToDecoderClass, enums);
		} else if (fd.isRepeated()) {
			supported = repeatedBody(body, fd, name, protoToDecoderClass, enums);
		} else {
			supported = singularBody(body, fd, name, protoToDecoderClass, enums);
		}
		if (supported) {
			o.out.append(body.out);
		} else {
			o.line(BAIL);
		}
		o.close("}");
	}

	private static boolean singularBody(Code o, FieldDescriptor fd, String name,
			Map<String, String> protoToDecoderClass, Map<String, EnumDescriptor> enums) {
		if (!value(o, fd, "v", "", protoToDecoderClass, enums)) {
			return false;
		}
		o.line("builder.set" + name + (isEnum(fd) ? "Value" : "") + "(v);");
		o.line("return p;");
		return true;
	}

	private static boolean repeatedBody(Code o, FieldDescriptor fd, String name,
			Map<String, String> protoToDecoderClass, Map<String, EnumDescriptor> enums) {
		Code element = new Code(new StringBuilder(), o.indent + "    ");
		if (!value(element, fd, "v", "", protoToDecoderClass, enums)) {
			return false;
		}
		o.line("if (b[p] != '[') " + BAIL);
		o.line("p++;");
		o.line("if (b[p] <= ' ') p = " + FI + ".ws(b, p);");
		o.line("if (b[p] == ']') return p + 1;");
		o.open("while (true) {");
		o.out.append(element.out);
		o.line("builder.add" + name + (isEnum(fd) ? "Value" : "") + "(v);");
		separator(o, ']');
		o.close("}");
		return true;
	}

	private static boolean mapBody(Code o, FieldDescriptor fd, String name, Map<String, String> protoToDecoderClass,
			Map<String, EnumDescriptor> enums) {
		FieldDescriptor keyFd = fd.getMessageType().findFieldByName("key");
		FieldDescriptor valueFd = fd.getMessageType().findFieldByName("value");
		Code entry = new Code(new StringBuilder(), o.indent + "    ");
		if (!key(entry, keyFd, "key")) {
			return false;
		}
		entry.line("if (b[p] <= ' ') p = " + FI + ".ws(b, p);");
		entry.line("if (b[p] != ':') " + BAIL);
		entry.line("p++;");
		entry.line("if (b[p] <= ' ') p = " + FI + ".ws(b, p);");
		if (!value(entry, valueFd, "v", "", protoToDecoderClass, enums)) {
			return false;
		}
		entry.line("builder.put" + name + (isEnum(valueFd) ? "Value" : "") + "(key, v);");
		o.line("if (b[p] != '{') " + BAIL);
		o.line("p++;");
		o.line("if (b[p] <= ' ') p = " + FI + ".ws(b, p);");
		o.line("if (b[p] == '}') return p + 1;");
		o.open("while (true) {");
		o.out.append(entry.out);
		separator(o, '}');
		o.close("}");
		return true;
	}

	/**
	 * After an element / entry: whitespace, then a comma (continue) or the closing
	 * bracket (return the position after it).
	 */
	private static void separator(Code o, char closing) {
		o.line("if (b[p] <= ' ') p = " + FI + ".ws(b, p);");
		o.line("byte ch = b[p];");
		o.open("if (ch == ',') {");
		o.line("p++;");
		o.line("if (b[p] <= ' ') p = " + FI + ".ws(b, p);");
		o.mid("} else if (ch == '" + closing + "') {");
		o.line("return p + 1;");
		o.mid("} else {");
		o.line(BAIL);
		o.close("}");
	}

	// --- values ---

	private static boolean isEnum(FieldDescriptor fd) {
		return fd.getJavaType() == FieldDescriptor.JavaType.ENUM;
	}

	private static boolean isValue(FieldDescriptor fd) {
		return fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE && !fd.isRepeated()
				&& "google.protobuf.Value".equals(fd.getMessageType().getFullName());
	}

	private static boolean isNullValue(FieldDescriptor fd) {
		return isEnum(fd) && !fd.isRepeated() && "google.protobuf.NullValue".equals(fd.getEnumType().getFullName());
	}

	private static boolean unsigned32(FieldDescriptor fd) {
		return fd.getType() == FieldDescriptor.Type.UINT32 || fd.getType() == FieldDescriptor.Type.FIXED32;
	}

	private static boolean unsigned64(FieldDescriptor fd) {
		return fd.getType() == FieldDescriptor.Type.UINT64 || fd.getType() == FieldDescriptor.Type.FIXED64;
	}

	/**
	 * Appends statements that read one value of {@code fd} at {@code p}, declare
	 * {@code var} for it and leave {@code p} just after it (not past trailing
	 * whitespace). Returns false, having appended nothing useful, if the kind has
	 * no fast reader.
	 */
	private static boolean value(Code o, FieldDescriptor fd, String var, String suffix,
			Map<String, String> protoToDecoderClass, Map<String, EnumDescriptor> enums) {
		switch (fd.getJavaType()) {
			case INT -> {
				o.line("long r" + suffix + " = " + FI + "." + (unsigned32(fd) ? "uint32At" : "int32At") + "(b, p);");
				o.line("int " + var + " = (int) (r" + suffix + " >> 32);");
				o.line("p = (int) r" + suffix + ";");
			}
			case LONG -> handOver(o, "long " + var + " = c." + (unsigned64(fd) ? "uint64" : "int64") + "();");
			case FLOAT -> handOver(o, "float " + var + " = c.float32();");
			case DOUBLE -> handOver(o, "double " + var + " = c.float64();");
			case BOOLEAN -> {
				o.line("boolean " + var + ";");
				o.line("if (" + FI + ".i4(b, p) == 0x65757274) { " + var + " = true; p += 4; }");
				o.line("else if (" + FI + ".i4(b, p) == 0x736c6166 && b[p + 4] == 'e') { " + var
						+ " = false; p += 5; }");
				o.line("else " + BAIL);
			}
			case STRING -> string(o, var, suffix);
			case BYTE_STRING -> handOver(o, "com.google.protobuf.ByteString " + var + " = c.bytes();");
			case ENUM -> {
				EnumDescriptor type = fd.getEnumType();
				enums.putIfAbsent(type.getFullName(), type);
				o.line("long r" + suffix + " = " + enumHelperName(type) + "(b, p);");
				o.line("int " + var + " = (int) (r" + suffix + " >> 32);");
				o.line("p = (int) r" + suffix + ";");
			}
			case MESSAGE -> {
				return message(o, fd.getMessageType(), var, suffix, protoToDecoderClass, enums);
			}
		}
		return true;
	}

	/** Runs a cursor method that reads at, and advances, the shared position. */
	private static void handOver(Code o, String statement) {
		o.line("c.pos(p);");
		// the declaration must come first so it stays visible; the position is read
		// back
		// right after it
		o.line(statement);
		o.line("p = c.pos();");
	}

	private static void string(Code o, String var, String suffix) {
		o.line("if (b[p] != '\"') " + BAIL);
		o.line("int e" + suffix + " = " + FI + ".plainStringEnd(b, p + 1);");
		o.line("String " + var + ";");
		o.open("if (e" + suffix + " >= 0) {");
		o.line(var + " = new String(b, p + 1, e" + suffix + " - p - 1, java.nio.charset.StandardCharsets.ISO_8859_1);");
		o.line("p = e" + suffix + " + 1;");
		o.mid("} else {");
		o.line("c.pos(p);");
		o.line(var + " = c.string();");
		o.line("p = c.pos();");
		o.close("}");
	}

	private static boolean message(Code o, Descriptor type, String var, String suffix,
			Map<String, String> protoToDecoderClass, Map<String, EnumDescriptor> enums) {
		switch (type.getFullName()) {
			case "google.protobuf.Timestamp" ->
				handOver(o, "com.google.protobuf.Timestamp " + var + " = c.timestamp();");
			case "google.protobuf.Duration" -> handOver(o, "com.google.protobuf.Duration " + var + " = c.duration();");
			case "google.protobuf.Empty" -> {
				o.line("if (b[p] != '{') " + BAIL);
				o.line("c.pos(p);");
				o.line("c.skipValue();");
				o.line("p = c.pos();");
				o.line("com.google.protobuf.Empty " + var + " = com.google.protobuf.Empty.getDefaultInstance();");
			}
			case "google.protobuf.DoubleValue" -> wrapper(o, type, "com.google.protobuf.DoubleValue", var, suffix);
			case "google.protobuf.FloatValue" -> wrapper(o, type, "com.google.protobuf.FloatValue", var, suffix);
			case "google.protobuf.Int64Value" -> wrapper(o, type, "com.google.protobuf.Int64Value", var, suffix);
			case "google.protobuf.UInt64Value" -> wrapper(o, type, "com.google.protobuf.UInt64Value", var, suffix);
			case "google.protobuf.Int32Value" -> wrapper(o, type, "com.google.protobuf.Int32Value", var, suffix);
			case "google.protobuf.UInt32Value" -> wrapper(o, type, "com.google.protobuf.UInt32Value", var, suffix);
			case "google.protobuf.BoolValue" -> wrapper(o, type, "com.google.protobuf.BoolValue", var, suffix);
			case "google.protobuf.StringValue" -> wrapper(o, type, "com.google.protobuf.StringValue", var, suffix);
			case "google.protobuf.BytesValue" -> wrapper(o, type, "com.google.protobuf.BytesValue", var, suffix);
			case "google.protobuf.Any", "google.protobuf.FieldMask", "google.protobuf.Struct", "google.protobuf.Value",
					"google.protobuf.ListValue" -> {
				return false;
			}
			default -> {
				String decoderClass = protoToDecoderClass.get(type.getFullName());
				if (decoderClass == null) {
					return false;
				}
				o.line("c.pos(p);");
				o.line("var " + var + " = " + decoderClass + ".INSTANCE.readFast(c);");
				o.line("p = c.pos();");
			}
		}
		return true;
	}

	/**
	 * A wrapper message is written as its bare value; the inner value is read like
	 * a field of that scalar type.
	 */
	private static void wrapper(Code o, Descriptor type, String wrapperClass, String var, String suffix) {
		FieldDescriptor inner = type.findFieldByName("value");
		value(o, inner, var + "Inner", suffix + "w", Map.of(), new LinkedHashMap<>());
		o.line(wrapperClass + " " + var + " = " + wrapperClass + ".of(" + var + "Inner);");
	}

	private static boolean key(Code o, FieldDescriptor keyFd, String var) {
		switch (keyFd.getJavaType()) {
			case STRING -> string(o, var, "k");
			case INT -> {
				o.line("long rk = " + FI + "." + (unsigned32(keyFd) ? "uint32KeyAt" : "int32KeyAt") + "(b, p);");
				o.line("int " + var + " = (int) (rk >> 32);");
				o.line("p = (int) rk;");
			}
			case LONG -> handOver(o, "long " + var + " = c." + (unsigned64(keyFd) ? "uint64Key" : "int64Key") + "();");
			case BOOLEAN -> handOver(o, "boolean " + var + " = c.boolKey();");
			default -> {
				return false;
			}
		}
		return true;
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
	private static void emitEnumHelpers(Code o, Map<String, EnumDescriptor> enums) {
		int index = 0;
		for (EnumDescriptor enumType : enums.values()) {
			int id = index++;
			o.blank();
			Map<Integer, List<com.google.protobuf.Descriptors.EnumValueDescriptor>> byLength = new LinkedHashMap<>();
			for (var value : enumType.getValues()) {
				byLength.computeIfAbsent(value.getName().length(), k -> new ArrayList<>()).add(value);
			}
			int constant = 0;
			Map<String, String> constants = new LinkedHashMap<>();
			for (var value : enumType.getValues()) {
				String name = "E" + id + "_" + constant++;
				constants.put(value.getName(), name);
				o.line("private static final byte[] " + name + " = " + SourceLiterals.javaString(value.getName())
						+ ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);");
			}
			o.open("private static long " + enumHelperName(enumType) + "(byte[] b, int p) {");
			o.line("if (b[p] != '\"') return " + FI + ".int32At(b, p);");
			o.line("int s = p + 1;");
			o.line("int e = " + FI + ".plainStringEnd(b, s);");
			o.line("if (e < 0) " + BAIL);
			o.line("int v;");
			o.open("switch (e - s) {");
			for (var group : byLength.entrySet()) {
				o.line("case " + group.getKey() + ":");
				for (var value : group.getValue()) {
					o.line("    if (java.util.Arrays.equals(b, s, e, " + constants.get(value.getName()) + ", 0, "
							+ group.getKey() + ")) { v = " + value.getNumber() + "; break; }");
				}
				o.line("    " + BAIL);
			}
			o.line("default:");
			o.line("    " + BAIL);
			o.close("}");
			o.line("return ((long) v << 32) | (e + 1);");
			o.close("}");
		}
	}
}
