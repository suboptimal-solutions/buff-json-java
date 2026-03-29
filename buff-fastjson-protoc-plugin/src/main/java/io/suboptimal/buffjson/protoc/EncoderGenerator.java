package io.suboptimal.buffjson.protoc;

import java.util.Map;
import java.util.Set;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;

/**
 * Generates a Java source file for a per-message-type JSON encoder. The
 * generated encoder implements {@code GeneratedEncoder<T>} and uses typed
 * accessors directly, avoiding reflection, boxing, and runtime type dispatch.
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
		sb.append("import io.suboptimal.buffjson.GeneratedEncoder;\n\n");
		sb.append("public final class ").append(encoderSimpleName);
		sb.append(" implements GeneratedEncoder<").append(messageClassName).append("> {\n\n");

		// Singleton instance for direct calls from other generated encoders
		sb.append("    public static final ").append(encoderSimpleName).append(" INSTANCE = new ")
				.append(encoderSimpleName).append("();\n\n");

		// Name char[] constants for writeNameRaw (works with both UTF-8 and UTF-16
		// writers)
		for (FieldDescriptor fd : msgDesc.getFields()) {
			if (fd.getOptions().hasDeprecated() && fd.getOptions().getDeprecated())
				continue;
			String jsonName = fd.getJsonName();
			sb.append("    private static final char[] NAME_").append(constantName(fd));
			sb.append(" = nameChars(\"").append(jsonName).append("\");\n");
		}
		sb.append("\n");

		// nameChars helper
		sb.append("    private static char[] nameChars(String name) {\n");
		sb.append("        char[] chars = new char[name.length() + 3];\n");
		sb.append("        chars[0] = '\"';\n");
		sb.append("        name.getChars(0, name.length(), chars, 1);\n");
		sb.append("        chars[name.length() + 1] = '\"';\n");
		sb.append("        chars[name.length() + 2] = ':';\n");
		sb.append("        return chars;\n");
		sb.append("    }\n\n");

		// descriptorFullName()
		sb.append("    @Override\n");
		sb.append("    public String descriptorFullName() {\n");
		sb.append("        return \"").append(msgDesc.getFullName()).append("\";\n");
		sb.append("    }\n\n");

		// writeFields()
		sb.append("    @Override\n");
		sb.append("    public void writeFields(JSONWriter jsonWriter, ").append(messageClassName)
				.append(" message) {\n");

		// Collect fields that are part of oneofs (we'll handle them via the oneof
		// switch)
		var oneofFields = new java.util.HashSet<FieldDescriptor>();
		for (OneofDescriptor oneof : msgDesc.getRealOneofs()) {
			oneofFields.addAll(oneof.getFields());
		}

		// Regular fields (not in oneof)
		for (FieldDescriptor fd : msgDesc.getFields()) {
			if (oneofFields.contains(fd))
				continue;
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

		// Oneof fields
		for (OneofDescriptor oneof : msgDesc.getRealOneofs()) {
			generateOneof(sb, oneof, messageClassName, protoToJavaClass, protoToEncoderClass);
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
				sb.append("                jsonWriter.writeNameRaw(").append(constName).append(");\n");
				writeIntValue(sb, fd, "v");
				sb.append("            }\n");
				sb.append("        }\n");
			}
			case LONG -> {
				sb.append("        {\n");
				sb.append("            long v = ").append(getter).append(";\n");
				sb.append("            if (v != 0L) {\n");
				sb.append("                jsonWriter.writeNameRaw(").append(constName).append(");\n");
				writeLongValue(sb, fd, "v");
				sb.append("            }\n");
				sb.append("        }\n");
			}
			case FLOAT -> {
				sb.append("        {\n");
				sb.append("            float v = ").append(getter).append(";\n");
				sb.append("            if (Float.floatToRawIntBits(v) != 0) {\n");
				sb.append("                jsonWriter.writeNameRaw(").append(constName).append(");\n");
				writeFloatValue(sb, "v");
				sb.append("            }\n");
				sb.append("        }\n");
			}
			case DOUBLE -> {
				sb.append("        {\n");
				sb.append("            double v = ").append(getter).append(";\n");
				sb.append("            if (Double.doubleToRawLongBits(v) != 0) {\n");
				sb.append("                jsonWriter.writeNameRaw(").append(constName).append(");\n");
				writeDoubleValue(sb, "v");
				sb.append("            }\n");
				sb.append("        }\n");
			}
			case BOOLEAN -> {
				sb.append("        if (").append(getter).append(") {\n");
				sb.append("            jsonWriter.writeNameRaw(").append(constName).append(");\n");
				sb.append("            jsonWriter.writeBool(true);\n");
				sb.append("        }\n");
			}
			case STRING -> {
				sb.append("        {\n");
				sb.append("            String v = ").append(getter).append(";\n");
				sb.append("            if (!v.isEmpty()) {\n");
				sb.append("                jsonWriter.writeNameRaw(").append(constName).append(");\n");
				sb.append("                jsonWriter.writeString(v);\n");
				sb.append("            }\n");
				sb.append("        }\n");
			}
			case BYTE_STRING -> {
				sb.append("        {\n");
				sb.append("            com.google.protobuf.ByteString v = ").append(getter).append(";\n");
				sb.append("            if (!v.isEmpty()) {\n");
				sb.append("                jsonWriter.writeNameRaw(").append(constName).append(");\n");
				sb.append(
						"                jsonWriter.writeString(java.util.Base64.getEncoder().encodeToString(v.toByteArray()));\n");
				sb.append("            }\n");
				sb.append("        }\n");
			}
			case ENUM -> {
				String enumClass = protoToJavaClass.get(fd.getEnumType().getFullName());
				sb.append("        {\n");
				sb.append("            int ev = message.").append(getterName(fd)).append("Value();\n");
				sb.append("            if (ev != 0) {\n");
				sb.append("                jsonWriter.writeNameRaw(").append(constName).append(");\n");
				writeEnumValue(sb, enumClass, "ev");
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
		sb.append("            jsonWriter.writeNameRaw(").append(constName).append(");\n");

		switch (fd.getJavaType()) {
			case INT -> writeIntValue(sb, fd, getter);
			case LONG -> writeLongValue(sb, fd, getter);
			case FLOAT -> writeFloatValue(sb, getter);
			case DOUBLE -> writeDoubleValue(sb, getter);
			case BOOLEAN -> sb.append("            jsonWriter.writeBool(").append(getter).append(");\n");
			case STRING -> sb.append("            jsonWriter.writeString(").append(getter).append(");\n");
			case BYTE_STRING ->
				sb.append("            jsonWriter.writeString(java.util.Base64.getEncoder().encodeToString(")
						.append(getter).append(".toByteArray()));\n");
			case ENUM -> {
				String enumClass = protoToJavaClass.get(fd.getEnumType().getFullName());
				sb.append("            {\n");
				sb.append("                int ev = message.").append(getterName(fd)).append("Value();\n");
				writeEnumValue(sb, enumClass, "ev");
				sb.append("            }\n");
			}
			case MESSAGE -> writeMessageValue(sb, fd, getter, protoToJavaClass, protoToEncoderClass);
		}

		sb.append("        }\n");
	}

	private static void generateRepeatedField(StringBuilder sb, FieldDescriptor fd, String msgClass,
			Map<String, String> protoToJavaClass, Map<String, String> protoToEncoderClass) {

		String listGetter = "message." + getterName(fd) + "List()";
		String constName = "NAME_" + constantName(fd);
		String elementType = javaBoxedType(fd, protoToJavaClass);

		sb.append("        {\n");
		sb.append("            java.util.List<").append(elementType).append("> values = ").append(listGetter)
				.append(";\n");
		sb.append("            if (!values.isEmpty()) {\n");
		sb.append("                jsonWriter.writeNameRaw(").append(constName).append(");\n");
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
			case BYTE_STRING -> sb.append(
					"                    jsonWriter.writeString(java.util.Base64.getEncoder().encodeToString(values.get(i).toByteArray()));\n");
			case ENUM -> {
				// Repeated enums: values are EnumValueDescriptor from getField,
				// but typed list returns the Java enum
				String enumClass = protoToJavaClass.get(fd.getEnumType().getFullName());
				sb.append("                    ").append(enumClass).append(" e = values.get(i);\n");
				sb.append("                    jsonWriter.writeString(e.getValueDescriptor().getName());\n");
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

		String mapGetter = "message." + getterName(fd) + "Map()";
		String constName = "NAME_" + constantName(fd);

		Descriptor entryDesc = fd.getMessageType();
		FieldDescriptor keyFd = entryDesc.findFieldByName("key");
		FieldDescriptor valueFd = entryDesc.findFieldByName("value");

		String keyType = javaBoxedType(keyFd, protoToJavaClass);
		String valueType = javaBoxedType(valueFd, protoToJavaClass);

		sb.append("        {\n");
		sb.append("            java.util.Map<").append(keyType).append(", ").append(valueType).append("> map = ")
				.append(mapGetter).append(";\n");
		sb.append("            if (!map.isEmpty()) {\n");
		sb.append("                jsonWriter.writeNameRaw(").append(constName).append(");\n");
		sb.append("                jsonWriter.startObject();\n");
		sb.append("                for (var entry : map.entrySet()) {\n");
		sb.append("                    jsonWriter.writeName(entry.getKey().toString());\n");
		sb.append("                    jsonWriter.writeColon();\n");

		switch (valueFd.getJavaType()) {
			case INT -> writeIntValue(sb, valueFd, "entry.getValue()");
			case LONG -> writeLongValue(sb, valueFd, "entry.getValue()");
			case FLOAT -> writeFloatValue(sb, "entry.getValue()");
			case DOUBLE -> writeDoubleValue(sb, "entry.getValue()");
			case BOOLEAN -> sb.append("                    jsonWriter.writeBool(entry.getValue());\n");
			case STRING -> sb.append("                    jsonWriter.writeString(entry.getValue());\n");
			case BYTE_STRING -> sb.append(
					"                    jsonWriter.writeString(java.util.Base64.getEncoder().encodeToString(entry.getValue().toByteArray()));\n");
			case ENUM -> {
				String enumClass = protoToJavaClass.get(valueFd.getEnumType().getFullName());
				sb.append("                    ").append(enumClass).append(" e = entry.getValue();\n");
				sb.append("                    jsonWriter.writeString(e.getValueDescriptor().getName());\n");
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
			sb.append("                jsonWriter.writeNameRaw(").append(constName).append(");\n");

			switch (fd.getJavaType()) {
				case INT -> writeIntValue(sb, fd, getter);
				case LONG -> writeLongValue(sb, fd, getter);
				case FLOAT -> writeFloatValue(sb, getter);
				case DOUBLE -> writeDoubleValue(sb, getter);
				case BOOLEAN -> sb.append("                jsonWriter.writeBool(").append(getter).append(");\n");
				case STRING -> sb.append("                jsonWriter.writeString(").append(getter).append(");\n");
				case BYTE_STRING ->
					sb.append("                jsonWriter.writeString(java.util.Base64.getEncoder().encodeToString(")
							.append(getter).append(".toByteArray()));\n");
				case ENUM -> {
					String enumClass = protoToJavaClass.get(fd.getEnumType().getFullName());
					sb.append("                {\n");
					sb.append("                    int ev = message.").append(getterName(fd)).append("Value();\n");
					writeEnumValue(sb, enumClass, "ev");
					sb.append("                }\n");
				}
				case MESSAGE -> writeMessageValue(sb, fd, getter, protoToJavaClass, protoToEncoderClass);
			}

			sb.append("            }\n");
		}

		String notSetValue = oneof.getName().toUpperCase() + "_NOT_SET";
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
			sb.append("                jsonWriter.writeString(Long.toUnsignedString(").append(expr).append("));\n");
		} else {
			sb.append("                jsonWriter.writeString(Long.toString(").append(expr).append("));\n");
		}
	}

	private static void writeFloatValue(StringBuilder sb, String expr) {
		sb.append("                {\n");
		sb.append("                    float fv = ").append(expr).append(";\n");
		sb.append("                    if (Float.isNaN(fv)) jsonWriter.writeString(\"NaN\");\n");
		sb.append(
				"                    else if (Float.isInfinite(fv)) jsonWriter.writeString(fv > 0 ? \"Infinity\" : \"-Infinity\");\n");
		sb.append("                    else jsonWriter.writeFloat(fv);\n");
		sb.append("                }\n");
	}

	private static void writeDoubleValue(StringBuilder sb, String expr) {
		sb.append("                {\n");
		sb.append("                    double dv = ").append(expr).append(";\n");
		sb.append("                    if (Double.isNaN(dv)) jsonWriter.writeString(\"NaN\");\n");
		sb.append(
				"                    else if (Double.isInfinite(dv)) jsonWriter.writeString(dv > 0 ? \"Infinity\" : \"-Infinity\");\n");
		sb.append("                    else jsonWriter.writeDouble(dv);\n");
		sb.append("                }\n");
	}

	private static void writeEnumValue(StringBuilder sb, String enumClass, String expr) {
		sb.append("                ").append(enumClass).append(" e = ").append(enumClass).append(".forNumber(")
				.append(expr).append(");\n");
		sb.append(
				"                jsonWriter.writeString(e != null ? e.getValueDescriptor().getName() : String.valueOf(")
				.append(expr).append("));\n");
	}

	private static void writeMessageValue(StringBuilder sb, FieldDescriptor fd, String expr,
			Map<String, String> protoToJavaClass, Map<String, String> protoToEncoderClass) {
		String fullName = fd.getMessageType().getFullName();
		if (WELL_KNOWN_TYPES.contains(fullName)) {
			sb.append("                io.suboptimal.buffjson.internal.WellKnownTypes.write(jsonWriter, ").append(expr)
					.append(");\n");
		} else {
			String encoderClass = protoToEncoderClass.get(fullName);
			if (encoderClass != null) {
				// Direct call to the nested type's generated encoder — bypasses registry
				// lookup, ThreadLocal reads, and instanceof checks
				sb.append("                jsonWriter.startObject();\n");
				sb.append("                ").append(encoderClass).append(".INSTANCE.writeFields(jsonWriter, ")
						.append(expr).append(");\n");
				sb.append("                jsonWriter.endObject();\n");
			} else {
				sb.append(
						"                io.suboptimal.buffjson.internal.ProtobufMessageWriter.INSTANCE.writeMessage(jsonWriter, ")
						.append(expr).append(");\n");
			}
		}
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
}
