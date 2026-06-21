package io.suboptimal.buffjson.protoc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;

/**
 * Generates a Java source file for a per-message-type JSON encoder. The
 * generated encoder implements {@code BuffJsonGeneratedEncoder<T>} and uses
 * typed accessors directly, avoiding reflection, boxing, and runtime type
 * dispatch.
 *
 * <p>
 * Generated encoders include these optimizations:
 * <ul>
 * <li><b>INSTANCE singleton</b> — enables direct calls from other generated
 * encoders
 * <li><b>Direct nested encoder calls</b> — nested messages call
 * {@code FooJsonEncoder.INSTANCE.writeFields(jw, msg, writer)} directly,
 * bypassing registry lookup and instanceof checks
 * <li><b>Inline WKT Timestamp/Duration</b> — calls
 * {@code WellKnownTypes.writeTimestampDirect()} with typed accessors, bypassing
 * descriptor string switch, field cache lookup, and reflection+boxing
 * <li><b>Pre-cached enum name arrays</b> — static {@code String[]} built from
 * enum descriptor values at class init, replacing
 * {@code forNumber()+getValueDescriptor().getName()} per write
 * <li><b>String map key optimization</b> — avoids redundant {@code toString()}
 * for String-typed map keys
 * <li><b>Native fastjson2 Base64</b> — uses {@code writeBase64(byte[])} for
 * bytes fields, encoding directly into the output buffer without intermediate
 * String
 * <li><b>Zero-allocation int64</b> — signed types use
 * {@code writeString(long)}, unsigned use
 * {@code WellKnownTypes.writeUnsignedLongString()} — no {@code Long.toString()}
 * or {@code Long.toUnsignedString()} allocation
 * </ul>
 */
final class EncoderGenerator {

	private static final Set<String> WELL_KNOWN_TYPES = BuffJsonProtocPlugin.WELL_KNOWN_TYPES;

	private EncoderGenerator() {
	}

	static String generate(Descriptor msgDesc, String javaPackage, String encoderSimpleName, String messageClassName,
			Map<String, String> protoToJavaClass, Map<String, String> protoToEncoderClass) {

		StringBuilder sb = new StringBuilder();
		sb.append("package ").append(javaPackage).append(";\n\n");
		sb.append("import com.alibaba.fastjson2.JSONWriter;\n");
		sb.append("import io.suboptimal.buffjson.BuffJsonGeneratedEncoder;\n\n");
		sb.append("public final class ").append(encoderSimpleName);
		sb.append(" implements BuffJsonGeneratedEncoder<").append(messageClassName).append("> {\n\n");

		// Singleton instance for direct calls from other generated encoders
		sb.append("    public static final ").append(encoderSimpleName).append(" INSTANCE = new ")
				.append(encoderSimpleName).append("();\n\n");

		// Name constants: char[] for UTF-16 writers, byte[] for UTF-8 writers.
		// Pre-encoded at class init; ASCII-only since proto field names are ASCII.
		for (FieldDescriptor fd : msgDesc.getFields()) {
			if (fd.getOptions().hasDeprecated() && fd.getOptions().getDeprecated())
				continue;
			String jsonName = fd.getJsonName();
			sb.append("    private static final char[] NAME_").append(constantName(fd));
			sb.append(" = nameChars(\"").append(jsonName).append("\");\n");
			sb.append("    private static final byte[] NAME_").append(constantName(fd));
			sb.append("_BYTES = nameBytes(\"").append(jsonName).append("\");\n");
		}
		sb.append("\n");

		// nameChars / nameBytes helpers
		sb.append("    private static char[] nameChars(String name) {\n");
		sb.append("        char[] chars = new char[name.length() + 3];\n");
		sb.append("        chars[0] = '\"';\n");
		sb.append("        name.getChars(0, name.length(), chars, 1);\n");
		sb.append("        chars[name.length() + 1] = '\"';\n");
		sb.append("        chars[name.length() + 2] = ':';\n");
		sb.append("        return chars;\n");
		sb.append("    }\n\n");
		sb.append("    private static byte[] nameBytes(String name) {\n");
		sb.append("        byte[] bytes = new byte[name.length() + 3];\n");
		sb.append("        bytes[0] = '\"';\n");
		sb.append("        for (int i = 0; i < name.length(); i++) bytes[i + 1] = (byte) name.charAt(i);\n");
		sb.append("        bytes[name.length() + 1] = '\"';\n");
		sb.append("        bytes[name.length() + 2] = ':';\n");
		sb.append("        return bytes;\n");
		sb.append("    }\n\n");

		// Pre-collect enum types used by int-valued fields (implicit presence,
		// explicit presence, oneof) so we can generate cached name arrays.
		// Key: enum constant prefix (e.g. "STATUS"), Value: Java enum class name
		Map<String, String> enumArrays = collectEnumTypes(msgDesc, protoToJavaClass);
		for (var entry : enumArrays.entrySet()) {
			sb.append("    private static final String[] ENUM_").append(entry.getKey()).append("_NAMES;\n");
		}
		if (!enumArrays.isEmpty()) {
			sb.append("    static {\n");
			for (var entry : enumArrays.entrySet()) {
				String enumClass = entry.getValue();
				// Each enum gets its own block so the edVals/max locals don't collide
				// when a message references several distinct enum types. Negative enum
				// values (e.g. NEG = -1) can't index the array — they're skipped here
				// and resolved by descriptor lookup at the write site instead. The
				// "== null" guard makes the first-declared name win for aliased enums
				// (allow_alias), matching findValueByNumber / JsonFormat's canonical name.
				sb.append("        {\n");
				sb.append("            var edVals = ").append(enumClass).append(".getDescriptor().getValues();\n");
				sb.append("            int max = 0;\n");
				sb.append("            for (var v : edVals) if (v.getNumber() > max) max = v.getNumber();\n");
				sb.append("            ENUM_").append(entry.getKey()).append("_NAMES = new String[max + 1];\n");
				sb.append("            for (var v : edVals) if (v.getNumber() >= 0 && ENUM_").append(entry.getKey())
						.append("_NAMES[v.getNumber()] == null) ENUM_").append(entry.getKey())
						.append("_NAMES[v.getNumber()] = v.getName();\n");
				sb.append("        }\n");
			}
			sb.append("    }\n");
		}
		if (!enumArrays.isEmpty())
			sb.append("\n");

		// writeFields()
		sb.append("    @Override\n");
		sb.append("    public void writeFields(JSONWriter jsonWriter, ").append(messageClassName)
				.append(" message, io.suboptimal.buffjson.internal.ProtobufMessageWriter writer) {\n");
		sb.append("        boolean utf8 = jsonWriter.isUTF8();\n");

		// Emit fields in descriptor (field-number) order, matching JsonFormat's
		// output order. A oneof's switch is emitted at the position of its
		// first-declared member field, then the remaining members are skipped —
		// keeping the single set oneof field in field-number order rather than
		// bunching all oneofs at the end.
		var emittedOneofs = new java.util.HashSet<OneofDescriptor>();
		for (FieldDescriptor fd : msgDesc.getFields()) {
			OneofDescriptor oneof = fd.getRealContainingOneof();
			if (oneof != null) {
				if (emittedOneofs.add(oneof)) {
					generateOneof(sb, oneof, messageClassName, protoToJavaClass, protoToEncoderClass);
				}
				continue;
			}
			if (fd.isMapField()) {
				generateMapField(sb, fd, messageClassName, protoToJavaClass, protoToEncoderClass);
			} else if (fd.isRepeated()) {
				generateRepeatedField(sb, fd, messageClassName, protoToJavaClass, protoToEncoderClass);
			} else if (fd.hasPresence()) {
				generatePresenceField(sb, fd, messageClassName, protoToJavaClass, protoToEncoderClass);
			} else {
				generateImplicitPresenceField(sb, fd, messageClassName, protoToJavaClass, protoToEncoderClass);
			}
		}

		sb.append("    }\n");
		sb.append("}\n");
		return sb.toString();
	}

	// --- Field code generation ---

	private static void generateImplicitPresenceField(StringBuilder sb, FieldDescriptor fd, String msgClass,
			Map<String, String> protoToJavaClass, Map<String, String> protoToEncoderClass) {

		String getter = "message." + getterName(fd) + "()";
		String constName = "NAME_" + constantName(fd);

		switch (fd.getJavaType()) {
			case INT -> {
				sb.append("        {\n");
				sb.append("            int v = ").append(getter).append(";\n");
				sb.append("            if (v != 0) {\n");
				emitWriteName(sb, constName, "                ");
				writeIntValue(sb, fd, "v");
				sb.append("            }\n");
				sb.append("        }\n");
			}
			case LONG -> {
				sb.append("        {\n");
				sb.append("            long v = ").append(getter).append(";\n");
				sb.append("            if (v != 0L) {\n");
				emitWriteName(sb, constName, "                ");
				writeLongValue(sb, fd, "v");
				sb.append("            }\n");
				sb.append("        }\n");
			}
			case FLOAT -> {
				sb.append("        {\n");
				sb.append("            float v = ").append(getter).append(";\n");
				sb.append("            if (Float.floatToRawIntBits(v) != 0) {\n");
				emitWriteName(sb, constName, "                ");
				writeFloatValue(sb, "v");
				sb.append("            }\n");
				sb.append("        }\n");
			}
			case DOUBLE -> {
				sb.append("        {\n");
				sb.append("            double v = ").append(getter).append(";\n");
				sb.append("            if (Double.doubleToRawLongBits(v) != 0) {\n");
				emitWriteName(sb, constName, "                ");
				writeDoubleValue(sb, "v");
				sb.append("            }\n");
				sb.append("        }\n");
			}
			case BOOLEAN -> {
				sb.append("        if (").append(getter).append(") {\n");
				emitWriteName(sb, constName, "            ");
				sb.append("            jsonWriter.writeBool(true);\n");
				sb.append("        }\n");
			}
			case STRING -> {
				sb.append("        {\n");
				sb.append("            String v = ").append(getter).append(";\n");
				sb.append("            if (!v.isEmpty()) {\n");
				emitWriteName(sb, constName, "                ");
				sb.append("                jsonWriter.writeString(v);\n");
				sb.append("            }\n");
				sb.append("        }\n");
			}
			case BYTE_STRING -> {
				sb.append("        {\n");
				sb.append("            com.google.protobuf.ByteString v = ").append(getter).append(";\n");
				sb.append("            if (!v.isEmpty()) {\n");
				emitWriteName(sb, constName, "                ");
				sb.append("                jsonWriter.writeBase64(v.toByteArray());\n");
				sb.append("            }\n");
				sb.append("        }\n");
			}
			case ENUM -> {
				sb.append("        {\n");
				sb.append("            int ev = message.").append(getterName(fd)).append("Value();\n");
				sb.append("            if (ev != 0) {\n");
				emitWriteName(sb, constName, "                ");
				writeEnumValue(sb, enumArrayConstant(fd), enumJavaClass(fd, protoToJavaClass), "ev");
				sb.append("            }\n");
				sb.append("        }\n");
			}
			case MESSAGE -> {
				// MESSAGE fields always have presence, so this shouldn't be reached
				// via implicit presence. Handle as presence field.
				generatePresenceField(sb, fd, msgClass, protoToJavaClass, protoToEncoderClass);
			}
		}
	}

	private static void generatePresenceField(StringBuilder sb, FieldDescriptor fd, String msgClass,
			Map<String, String> protoToJavaClass, Map<String, String> protoToEncoderClass) {

		String hasGetter = "message." + hasName(fd) + "()";
		String getter = "message." + getterName(fd) + "()";
		String constName = "NAME_" + constantName(fd);

		sb.append("        if (").append(hasGetter).append(") {\n");
		emitWriteName(sb, constName, "            ");

		switch (fd.getJavaType()) {
			case INT -> writeIntValue(sb, fd, getter);
			case LONG -> writeLongValue(sb, fd, getter);
			case FLOAT -> writeFloatValue(sb, getter);
			case DOUBLE -> writeDoubleValue(sb, getter);
			case BOOLEAN -> sb.append("            jsonWriter.writeBool(").append(getter).append(");\n");
			case STRING -> sb.append("            jsonWriter.writeString(").append(getter).append(");\n");
			case BYTE_STRING ->
				sb.append("            jsonWriter.writeBase64(").append(getter).append(".toByteArray());\n");
			case ENUM -> {
				sb.append("            {\n");
				sb.append("                int ev = message.").append(getterName(fd)).append("Value();\n");
				writeEnumValue(sb, enumArrayConstant(fd), enumJavaClass(fd, protoToJavaClass), "ev");
				sb.append("            }\n");
			}
			case MESSAGE -> writeMessageValue(sb, fd, getter, protoToJavaClass, protoToEncoderClass);
		}

		sb.append("        }\n");
	}

	private static void generateRepeatedField(StringBuilder sb, FieldDescriptor fd, String msgClass,
			Map<String, String> protoToJavaClass, Map<String, String> protoToEncoderClass) {

		String constName = "NAME_" + constantName(fd);

		// For enums, use raw int value list to handle UNRECOGNIZED constants
		String listGetter;
		String elementType;
		if (fd.getJavaType() == FieldDescriptor.JavaType.ENUM) {
			listGetter = "message." + getterName(fd) + "ValueList()";
			elementType = "Integer";
		} else {
			listGetter = "message." + getterName(fd) + "List()";
			elementType = javaBoxedType(fd, protoToJavaClass);
		}

		sb.append("        {\n");
		sb.append("            java.util.List<").append(elementType).append("> values = ").append(listGetter)
				.append(";\n");
		sb.append("            if (!values.isEmpty()) {\n");
		emitWriteName(sb, constName, "                ");
		sb.append("                jsonWriter.startArray();\n");
		sb.append("                for (int i = 0; i < values.size(); i++) {\n");
		sb.append("                    if (i > 0) jsonWriter.writeComma();\n");

		switch (fd.getJavaType()) {
			case INT -> writeIntValue(sb, fd, "values.get(i)");
			case LONG -> writeLongValue(sb, fd, "values.get(i)");
			case FLOAT -> writeFloatValue(sb, "values.get(i)");
			case DOUBLE -> writeDoubleValue(sb, "values.get(i)");
			case BOOLEAN -> sb.append("                    jsonWriter.writeBool(values.get(i));\n");
			case STRING -> sb.append("                    jsonWriter.writeString(values.get(i));\n");
			case BYTE_STRING -> sb.append("                    jsonWriter.writeBase64(values.get(i).toByteArray());\n");
			case ENUM -> {
				// Use raw int values to handle UNRECOGNIZED enum constants
				// (which throw from getNumber()/getValueDescriptor())
				writeEnumValue(sb, enumArrayConstant(fd), enumJavaClass(fd, protoToJavaClass), "values.get(i)");
			}
			case MESSAGE -> writeMessageValue(sb, fd, "values.get(i)", protoToJavaClass, protoToEncoderClass);
		}

		sb.append("                }\n");
		sb.append("                jsonWriter.endArray();\n");
		sb.append("            }\n");
		sb.append("        }\n");
	}

	private static void generateMapField(StringBuilder sb, FieldDescriptor fd, String msgClass,
			Map<String, String> protoToJavaClass, Map<String, String> protoToEncoderClass) {

		String constName = "NAME_" + constantName(fd);

		Descriptor entryDesc = fd.getMessageType();
		FieldDescriptor keyFd = entryDesc.findFieldByName("key");
		FieldDescriptor valueFd = entryDesc.findFieldByName("value");

		// For enum-valued maps, use ValueMap() getter (returns Map<K, Integer>)
		// to safely handle UNRECOGNIZED enum constants
		String mapGetter;
		String keyType = javaBoxedType(keyFd, protoToJavaClass);
		String valueType;
		if (valueFd.getJavaType() == FieldDescriptor.JavaType.ENUM) {
			mapGetter = "message." + getterName(fd) + "ValueMap()";
			valueType = "Integer";
		} else {
			mapGetter = "message." + getterName(fd) + "Map()";
			valueType = javaBoxedType(valueFd, protoToJavaClass);
		}

		sb.append("        {\n");
		sb.append("            java.util.Map<").append(keyType).append(", ").append(valueType).append("> map = ")
				.append(mapGetter).append(";\n");
		sb.append("            if (!map.isEmpty()) {\n");
		emitWriteName(sb, constName, "                ");
		sb.append("                jsonWriter.startObject();\n");
		sb.append("                for (var entry : map.entrySet()) {\n");
		if (keyFd.getJavaType() == FieldDescriptor.JavaType.STRING) {
			// Key is already String — call writeName directly without toString()
			sb.append("                    jsonWriter.writeName(entry.getKey());\n");
		} else {
			sb.append("                    jsonWriter.writeName(entry.getKey().toString());\n");
		}
		sb.append("                    jsonWriter.writeColon();\n");

		switch (valueFd.getJavaType()) {
			case INT -> writeIntValue(sb, valueFd, "entry.getValue()");
			case LONG -> writeLongValue(sb, valueFd, "entry.getValue()");
			case FLOAT -> writeFloatValue(sb, "entry.getValue()");
			case DOUBLE -> writeDoubleValue(sb, "entry.getValue()");
			case BOOLEAN -> sb.append("                    jsonWriter.writeBool(entry.getValue());\n");
			case STRING -> sb.append("                    jsonWriter.writeString(entry.getValue());\n");
			case BYTE_STRING ->
				sb.append("                    jsonWriter.writeBase64(entry.getValue().toByteArray());\n");
			case ENUM -> {
				// Map enum values: entry.getValue() is an Integer (raw int) since
				// we use the ValueMap getter. Look up in pre-cached name array.
				writeEnumValue(sb, "ENUM_" + valueFd.getEnumType().getName().toUpperCase() + "_NAMES",
						enumJavaClass(valueFd, protoToJavaClass), "entry.getValue()");
			}
			case MESSAGE -> writeMessageValue(sb, valueFd, "entry.getValue()", protoToJavaClass, protoToEncoderClass);
		}

		sb.append("                }\n");
		sb.append("                jsonWriter.endObject();\n");
		sb.append("            }\n");
		sb.append("        }\n");
	}

	private static void generateOneof(StringBuilder sb, OneofDescriptor oneof, String msgClass,
			Map<String, String> protoToJavaClass, Map<String, String> protoToEncoderClass) {

		String caseGetter = "message.get" + BuffJsonProtocPlugin.toCamelCase(oneof.getName()) + "Case()";

		sb.append("        switch (").append(caseGetter).append(") {\n");

		for (FieldDescriptor fd : oneof.getFields()) {
			String enumValue = fd.getName().toUpperCase();
			String constName = "NAME_" + constantName(fd);
			String getter = "message." + getterName(fd) + "()";

			sb.append("            case ").append(enumValue).append(" -> {\n");
			emitWriteName(sb, constName, "                ");

			switch (fd.getJavaType()) {
				case INT -> writeIntValue(sb, fd, getter);
				case LONG -> writeLongValue(sb, fd, getter);
				case FLOAT -> writeFloatValue(sb, getter);
				case DOUBLE -> writeDoubleValue(sb, getter);
				case BOOLEAN -> sb.append("                jsonWriter.writeBool(").append(getter).append(");\n");
				case STRING -> sb.append("                jsonWriter.writeString(").append(getter).append(");\n");
				case BYTE_STRING ->
					sb.append("                jsonWriter.writeBase64(").append(getter).append(".toByteArray());\n");
				case ENUM -> {
					sb.append("                {\n");
					sb.append("                    int ev = message.").append(getterName(fd)).append("Value();\n");
					writeEnumValue(sb, enumArrayConstant(fd), enumJavaClass(fd, protoToJavaClass), "ev");
					sb.append("                }\n");
				}
				case MESSAGE -> writeMessageValue(sb, fd, getter, protoToJavaClass, protoToEncoderClass);
			}

			sb.append("            }\n");
		}

		// protobuf-java's oneof case enum names the not-set value after the
		// camelCased oneof name (oneof_field -> ONEOFFIELD_NOT_SET), not the raw
		// snake_case name.
		String notSetValue = BuffJsonProtocPlugin.toCamelCase(oneof.getName()).toUpperCase() + "_NOT_SET";
		sb.append("            case ").append(notSetValue).append(" -> {}\n");
		sb.append("        }\n");
	}

	// --- Value writing helpers ---

	private static void writeIntValue(StringBuilder sb, FieldDescriptor fd, String expr) {
		var type = fd.getType();
		if (type == FieldDescriptor.Type.UINT32 || type == FieldDescriptor.Type.FIXED32) {
			sb.append("                jsonWriter.writeInt64(Integer.toUnsignedLong(").append(expr).append("));\n");
		} else {
			sb.append("                jsonWriter.writeInt32(").append(expr).append(");\n");
		}
	}

	private static void writeLongValue(StringBuilder sb, FieldDescriptor fd, String expr) {
		var type = fd.getType();
		if (type == FieldDescriptor.Type.UINT64 || type == FieldDescriptor.Type.FIXED64) {
			sb.append(
					"                io.suboptimal.buffjson.internal.WellKnownTypes.writeUnsignedLongString(jsonWriter, ")
					.append(expr).append(");\n");
		} else {
			sb.append("                jsonWriter.writeString(").append(expr).append(");\n");
		}
	}

	private static void writeFloatValue(StringBuilder sb, String expr) {
		sb.append("                {\n");
		sb.append("                    float fv = ").append(expr).append(";\n");
		sb.append("                    if (Float.isFinite(fv)) jsonWriter.writeFloat(fv);\n");
		sb.append("                    else if (Float.isNaN(fv)) jsonWriter.writeString(\"NaN\");\n");
		sb.append("                    else jsonWriter.writeString(fv > 0 ? \"Infinity\" : \"-Infinity\");\n");
		sb.append("                }\n");
	}

	private static void writeDoubleValue(StringBuilder sb, String expr) {
		sb.append("                {\n");
		sb.append("                    double dv = ").append(expr).append(";\n");
		sb.append("                    if (Double.isFinite(dv)) jsonWriter.writeDouble(dv);\n");
		sb.append("                    else if (Double.isNaN(dv)) jsonWriter.writeString(\"NaN\");\n");
		sb.append("                    else jsonWriter.writeString(dv > 0 ? \"Infinity\" : \"-Infinity\");\n");
		sb.append("                }\n");
	}

	private static void writeEnumValue(StringBuilder sb, String enumArrayConstant, String enumClass, String expr) {
		if ("com.google.protobuf.NullValue".equals(enumClass)) {
			// proto3 JSON: google.protobuf.NullValue always serializes as JSON null.
			sb.append("                jsonWriter.writeNull();\n");
			return;
		}
		sb.append("                {\n");
		sb.append("                    int en = ").append(expr).append(";\n");
		sb.append("                    String[] names = ").append(enumArrayConstant).append(";\n");
		sb.append("                    String enm = en >= 0 && en < names.length ? names[en] : null;\n");
		// Fast path is the dense non-negative array; fall back to a descriptor lookup
		// for named negative values (e.g. NEG = -1) so they serialize by name, while
		// genuinely unknown numbers still serialize as the integer.
		sb.append("                    if (enm == null) {\n");
		sb.append("                        var evd = ").append(enumClass)
				.append(".getDescriptor().findValueByNumber(en);\n");
		sb.append("                        if (evd != null) enm = evd.getName();\n");
		sb.append("                    }\n");
		sb.append("                    if (enm != null) jsonWriter.writeString(enm);\n");
		sb.append("                    else jsonWriter.writeInt32(en);\n");
		sb.append("                }\n");
	}

	private static String enumJavaClass(FieldDescriptor fd, Map<String, String> protoToJavaClass) {
		return protoToJavaClass.get(fd.getEnumType().getFullName());
	}

	private static void writeMessageValue(StringBuilder sb, FieldDescriptor fd, String expr,
			Map<String, String> protoToJavaClass, Map<String, String> protoToEncoderClass) {
		String fullName = fd.getMessageType().getFullName();
		if ("google.protobuf.Timestamp".equals(fullName)) {
			// Direct typed access — bypasses descriptor lookup and reflection
			sb.append("                {\n");
			sb.append("                    var ts = ").append(expr).append(";\n");
			sb.append(
					"                    io.suboptimal.buffjson.internal.WellKnownTypes.writeTimestampDirect(jsonWriter, ts.getSeconds(), ts.getNanos());\n");
			sb.append("                }\n");
		} else if ("google.protobuf.Duration".equals(fullName)) {
			sb.append("                {\n");
			sb.append("                    var dur = ").append(expr).append(";\n");
			sb.append(
					"                    io.suboptimal.buffjson.internal.WellKnownTypes.writeDurationDirect(jsonWriter, dur.getSeconds(), dur.getNanos());\n");
			sb.append("                }\n");
		} else if (WELL_KNOWN_TYPES.contains(fullName)) {
			sb.append("                io.suboptimal.buffjson.internal.WellKnownTypes.write(jsonWriter, ").append(expr)
					.append(", writer);\n");
		} else {
			String encoderClass = protoToEncoderClass.get(fullName);
			if (encoderClass != null) {
				// Direct call to the nested type's generated encoder — bypasses registry
				// lookup and instanceof checks
				sb.append("                jsonWriter.startObject();\n");
				sb.append("                ").append(encoderClass).append(".INSTANCE.writeFields(jsonWriter, ")
						.append(expr).append(", writer);\n");
				sb.append("                jsonWriter.endObject();\n");
			} else {
				sb.append("                writer.writeMessage(jsonWriter, ").append(expr).append(");\n");
			}
		}
	}

	/**
	 * Emits the field-name write — dispatches on the JSONWriter type so UTF-8
	 * writers consume pre-encoded byte[] without char→byte transcoding.
	 */
	private static void emitWriteName(StringBuilder sb, String constName, String indent) {
		sb.append(indent).append("if (utf8) jsonWriter.writeNameRaw(").append(constName)
				.append("_BYTES); else jsonWriter.writeNameRaw(").append(constName).append(");\n");
	}

	// --- Naming helpers ---

	private static String getterName(FieldDescriptor fd) {
		return "get" + BuffJsonProtocPlugin.toCamelCase(fd.getName());
	}

	private static String hasName(FieldDescriptor fd) {
		return "has" + BuffJsonProtocPlugin.toCamelCase(fd.getName());
	}

	private static String constantName(FieldDescriptor fd) {
		return fd.getName().toUpperCase();
	}

	private static String javaBoxedType(FieldDescriptor fd, Map<String, String> protoToJavaClass) {
		return switch (fd.getJavaType()) {
			case INT -> "Integer";
			case LONG -> "Long";
			case FLOAT -> "Float";
			case DOUBLE -> "Double";
			case BOOLEAN -> "Boolean";
			case STRING -> "String";
			case BYTE_STRING -> "com.google.protobuf.ByteString";
			case ENUM -> protoToJavaClass.getOrDefault(fd.getEnumType().getFullName(), "Integer");
			case MESSAGE ->
				protoToJavaClass.getOrDefault(fd.getMessageType().getFullName(), "com.google.protobuf.Message");
		};
	}

	/**
	 * Returns the static array constant name for a field's enum type (e.g.
	 * "ENUM_STATUS_NAMES").
	 */
	private static String enumArrayConstant(FieldDescriptor fd) {
		return "ENUM_" + fd.getEnumType().getName().toUpperCase() + "_NAMES";
	}

	/**
	 * Collects unique enum types used across the message descriptor (scalar,
	 * repeated, map value, and oneof fields). Returns a map from array constant
	 * prefix (e.g. "STATUS") to fully-qualified Java enum class name.
	 */
	private static Map<String, String> collectEnumTypes(Descriptor msgDesc, Map<String, String> protoToJavaClass) {
		Map<String, String> enums = new LinkedHashMap<>();
		for (FieldDescriptor fd : msgDesc.getFields()) {
			if (fd.getJavaType() == FieldDescriptor.JavaType.ENUM) {
				String key = fd.getEnumType().getName().toUpperCase();
				enums.putIfAbsent(key, protoToJavaClass.get(fd.getEnumType().getFullName()));
			} else if (fd.isMapField()) {
				// Check map value type for enum
				FieldDescriptor valueFd = fd.getMessageType().findFieldByName("value");
				if (valueFd.getJavaType() == FieldDescriptor.JavaType.ENUM) {
					String key = valueFd.getEnumType().getName().toUpperCase();
					enums.putIfAbsent(key, protoToJavaClass.get(valueFd.getEnumType().getFullName()));
				}
			}
		}
		for (OneofDescriptor oneof : msgDesc.getRealOneofs()) {
			for (FieldDescriptor fd : oneof.getFields()) {
				if (fd.getJavaType() == FieldDescriptor.JavaType.ENUM) {
					String key = fd.getEnumType().getName().toUpperCase();
					enums.putIfAbsent(key, protoToJavaClass.get(fd.getEnumType().getFullName()));
				}
			}
		}
		return enums;
	}
}
