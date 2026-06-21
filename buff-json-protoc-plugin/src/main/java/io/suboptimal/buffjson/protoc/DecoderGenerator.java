package io.suboptimal.buffjson.protoc;

import java.util.Map;
import java.util.Set;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

/**
 * Generates a Java source file for a per-message-type JSON decoder. The
 * generated decoder implements {@code BuffJsonGeneratedDecoder<T>} and uses
 * typed setters on the Builder directly, avoiding reflection and runtime type
 * dispatch.
 */
final class DecoderGenerator {

	private static final Set<String> WELL_KNOWN_TYPES = BuffJsonProtocPlugin.WELL_KNOWN_TYPES;

	private DecoderGenerator() {
	}

	static String generate(Descriptor msgDesc, String javaPackage, String decoderSimpleName, String messageClassName,
			Map<String, String> protoToJavaClass, Map<String, String> protoToDecoderClass) {

		StringBuilder sb = new StringBuilder();
		sb.append("package ").append(javaPackage).append(";\n\n");
		sb.append("import com.alibaba.fastjson2.JSONReader;\n");
		sb.append("import io.suboptimal.buffjson.BuffJsonGeneratedDecoder;\n\n");
		sb.append("public final class ").append(decoderSimpleName);
		sb.append(" implements BuffJsonGeneratedDecoder<").append(messageClassName).append("> {\n\n");

		sb.append("    public static final ").append(decoderSimpleName).append(" INSTANCE = new ")
				.append(decoderSimpleName).append("();\n\n");

		sb.append("    @Override\n");
		sb.append("    public ").append(messageClassName).append(
				" readMessage(JSONReader reader, io.suboptimal.buffjson.internal.ProtobufMessageReader msgReader) {\n");
		sb.append("        ").append(messageClassName).append(".Builder builder = ").append(messageClassName)
				.append(".newBuilder();\n");
		sb.append("        reader.nextIfObjectStart();\n");
		sb.append("        while (!reader.nextIfObjectEnd()) {\n");
		sb.append("            String fieldName = reader.readFieldName();\n");
		sb.append("            if (fieldName == null) break;\n");

		// A null JSON value normally means "absent" (skip), but for
		// google.protobuf.Value and google.protobuf.NullValue fields it is meaningful
		// (NullValue). When the message has such fields we can't blanket-skip nulls;
		// each field decides instead.
		boolean nullSensitive = msgDesc.getFields().stream().anyMatch(fd -> isValueField(fd) || isNullValueField(fd));

		if (!nullSensitive) {
			sb.append("            if (reader.nextIfNull()) continue;\n");
		}

		sb.append("            switch (fieldName) {\n");

		for (FieldDescriptor fd : msgDesc.getFields()) {
			String jsonName = fd.getJsonName();
			sb.append("                case \"").append(jsonName).append("\"");
			if (!fd.getName().equals(jsonName)) {
				sb.append(", \"").append(fd.getName()).append("\"");
			}
			sb.append(" -> ");

			if (fd.isMapField()) {
				generateMapFieldRead(sb, fd, protoToJavaClass, protoToDecoderClass, nullSensitive);
			} else if (fd.isRepeated()) {
				generateRepeatedFieldRead(sb, fd, protoToJavaClass, protoToDecoderClass, nullSensitive);
			} else {
				generateScalarFieldRead(sb, fd, protoToJavaClass, protoToDecoderClass, nullSensitive);
			}
		}

		sb.append("                default -> { if (!reader.nextIfNull()) reader.skipValue(); }\n");
		sb.append("            }\n");
		sb.append("        }\n");
		sb.append("        return builder.build();\n");
		sb.append("    }\n");
		sb.append("}\n");
		return sb.toString();
	}

	// --- Scalar field ---

	private static void generateScalarFieldRead(StringBuilder sb, FieldDescriptor fd,
			Map<String, String> protoToJavaClass, Map<String, String> protoToDecoderClass, boolean nullSensitive) {

		String setter = "builder.set" + BuffJsonProtocPlugin.toCamelCase(fd.getName());
		sb.append("{\n");

		if (nullSensitive && isValueField(fd)) {
			sb.append("                    if (reader.nextIfNull()) {\n");
			sb.append("                        ").append(setter).append(
					"(com.google.protobuf.Value.newBuilder().setNullValue(com.google.protobuf.NullValue.NULL_VALUE).build());\n");
			sb.append("                    } else {\n");
			emitValueRead(sb, fd, setter, protoToJavaClass, protoToDecoderClass, "                        ");
			sb.append("                    }\n");
		} else if (nullSensitive && isNullValueField(fd)) {
			// proto3 JSON: a null for a google.protobuf.NullValue field means NULL_VALUE
			// (and, in a oneof, marks the case as set). Enum fields use the Value setter.
			sb.append("                    if (reader.nextIfNull()) {\n");
			sb.append("                        ").append(setter).append("Value(0);\n");
			sb.append("                    } else {\n");
			emitValueRead(sb, fd, setter, protoToJavaClass, protoToDecoderClass, "                        ");
			sb.append("                    }\n");
		} else if (nullSensitive) {
			sb.append("                    if (!reader.nextIfNull()) {\n");
			emitValueRead(sb, fd, setter, protoToJavaClass, protoToDecoderClass, "                        ");
			sb.append("                    }\n");
		} else {
			emitValueRead(sb, fd, setter, protoToJavaClass, protoToDecoderClass, "                    ");
		}

		sb.append("                }\n");
	}

	// --- Repeated field ---

	private static void generateRepeatedFieldRead(StringBuilder sb, FieldDescriptor fd,
			Map<String, String> protoToJavaClass, Map<String, String> protoToDecoderClass, boolean nullSensitive) {

		String adder = "builder.add" + BuffJsonProtocPlugin.toCamelCase(fd.getName());
		sb.append("{\n");

		String indent = nullSensitive ? "                    " : "                ";
		if (nullSensitive) {
			sb.append("                    if (!reader.nextIfNull()) {\n");
		}

		sb.append(indent).append("    reader.nextIfArrayStart();\n");
		sb.append(indent).append("    while (!reader.nextIfArrayEnd()) {\n");
		emitValueRead(sb, fd, adder, protoToJavaClass, protoToDecoderClass, indent + "        ");
		sb.append(indent).append("    }\n");

		if (nullSensitive) {
			sb.append("                    }\n");
		}
		sb.append("                }\n");
	}

	// --- Map field ---

	private static void generateMapFieldRead(StringBuilder sb, FieldDescriptor fd, Map<String, String> protoToJavaClass,
			Map<String, String> protoToDecoderClass, boolean nullSensitive) {

		Descriptor entryDesc = fd.getMessageType();
		FieldDescriptor keyFd = entryDesc.findFieldByName("key");
		FieldDescriptor valueFd = entryDesc.findFieldByName("value");

		String putter = "builder.put" + BuffJsonProtocPlugin.toCamelCase(fd.getName());
		sb.append("{\n");

		String indent = nullSensitive ? "                    " : "                ";
		if (nullSensitive) {
			sb.append("                    if (!reader.nextIfNull()) {\n");
		}

		sb.append(indent).append("    reader.nextIfObjectStart();\n");
		sb.append(indent).append("    while (!reader.nextIfObjectEnd()) {\n");
		sb.append(indent).append("        String keyStr = reader.readFieldName();\n");
		sb.append(indent).append("        if (keyStr == null) break;\n");
		sb.append(indent).append("        if (reader.nextIfNull()) continue;\n");

		String keyExpr = mapKeyExpr(keyFd);
		String mapTarget = putter + "(" + keyExpr + ", ";

		if (valueFd.getJavaType() == FieldDescriptor.JavaType.ENUM) {
			// Enum maps use putXxxValue(key, int) for unrecognized enum support
			String valuePutter = putter + "Value(" + keyExpr + ", ";
			String enumClass = protoToJavaClass.get(valueFd.getEnumType().getFullName());
			sb.append(indent).append("        if (reader.isString()) {\n");
			sb.append(indent).append("            ").append(valuePutter)
					.append("io.suboptimal.buffjson.internal.FieldReader.enumNumber(reader, ").append(enumClass)
					.append(".getDescriptor(), reader.readString()));\n");
			sb.append(indent).append("        } else {\n");
			sb.append(indent).append("            ").append(valuePutter).append("reader.readInt32Value());\n");
			sb.append(indent).append("        }\n");
		} else {
			emitValueRead(sb, valueFd, mapTarget, protoToJavaClass, protoToDecoderClass, indent + "        ", ")");
		}

		sb.append(indent).append("    }\n");

		if (nullSensitive) {
			sb.append("                    }\n");
		}
		sb.append("                }\n");
	}

	// --- Shared value read emission ---

	/**
	 * Emits a read expression for any field type. The emitted code looks like:
	 * {@code <prefix><readExpr>;<suffix>}. For most types suffix is ";\n", for map
	 * values it may be ");\n" to close the putter call.
	 */
	private static void emitValueRead(StringBuilder sb, FieldDescriptor fd, String prefix,
			Map<String, String> protoToJavaClass, Map<String, String> protoToDecoderClass, String indent) {
		emitValueRead(sb, fd, prefix, protoToJavaClass, protoToDecoderClass, indent, "");
	}

	private static void emitValueRead(StringBuilder sb, FieldDescriptor fd, String prefix,
			Map<String, String> protoToJavaClass, Map<String, String> protoToDecoderClass, String indent,
			String closeSuffix) {

		switch (fd.getJavaType()) {
			case INT -> {
				var type = fd.getType();
				if (type == FieldDescriptor.Type.UINT32 || type == FieldDescriptor.Type.FIXED32) {
					sb.append(indent).append(prefix).append("((int) reader.readInt64Value()").append(closeSuffix)
							.append(");\n");
				} else {
					sb.append(indent).append(prefix).append("(reader.readInt32Value()").append(closeSuffix)
							.append(");\n");
				}
			}
			case LONG -> {
				var type = fd.getType();
				if (type == FieldDescriptor.Type.UINT64 || type == FieldDescriptor.Type.FIXED64) {
					sb.append(indent).append(prefix)
							.append("(io.suboptimal.buffjson.internal.FieldReader.readUnsignedLong(reader)")
							.append(closeSuffix).append(");\n");
				} else {
					sb.append(indent).append(prefix)
							.append("(io.suboptimal.buffjson.internal.FieldReader.readSignedLong(reader)")
							.append(closeSuffix).append(");\n");
				}
			}
			case FLOAT -> sb.append(indent).append(prefix)
					.append("(io.suboptimal.buffjson.internal.FieldReader.readFloatValue(reader)").append(closeSuffix)
					.append(");\n");
			case DOUBLE -> sb.append(indent).append(prefix)
					.append("(io.suboptimal.buffjson.internal.FieldReader.readDoubleValue(reader)").append(closeSuffix)
					.append(");\n");
			case BOOLEAN ->
				sb.append(indent).append(prefix).append("(reader.readBoolValue()").append(closeSuffix).append(");\n");
			case STRING ->
				sb.append(indent).append(prefix).append("(reader.readString()").append(closeSuffix).append(");\n");
			case BYTE_STRING -> sb.append(indent).append(prefix)
					.append("(io.suboptimal.buffjson.internal.FieldReader.readBytes(reader)").append(closeSuffix)
					.append(");\n");
			case ENUM -> {
				// Enum fields use the Value variant: setFoo -> setFooValue, addFoo ->
				// addFooValue
				String valueName = prefix + "Value";
				String enumClass = protoToJavaClass.get(fd.getEnumType().getFullName());
				sb.append(indent).append("if (reader.isString()) {\n");
				sb.append(indent).append("    ").append(valueName)
						.append("(io.suboptimal.buffjson.internal.FieldReader.enumNumber(reader, ").append(enumClass)
						.append(".getDescriptor(), reader.readString())").append(closeSuffix).append(");\n");
				sb.append(indent).append("} else {\n");
				sb.append(indent).append("    ").append(valueName).append("(reader.readInt32Value()")
						.append(closeSuffix).append(");\n");
				sb.append(indent).append("}\n");
			}
			case MESSAGE -> emitMessageRead(sb, fd, prefix, protoToJavaClass, protoToDecoderClass, indent, closeSuffix);
		}
	}

	private static void emitMessageRead(StringBuilder sb, FieldDescriptor fd, String prefix,
			Map<String, String> protoToJavaClass, Map<String, String> protoToDecoderClass, String indent,
			String closeSuffix) {

		String fullName = fd.getMessageType().getFullName();
		if ("google.protobuf.Timestamp".equals(fullName)) {
			sb.append(indent).append(prefix)
					.append("(io.suboptimal.buffjson.internal.WellKnownTypes.readTimestamp(reader)").append(closeSuffix)
					.append(");\n");
		} else if ("google.protobuf.Duration".equals(fullName)) {
			sb.append(indent).append(prefix)
					.append("(io.suboptimal.buffjson.internal.WellKnownTypes.readDuration(reader)").append(closeSuffix)
					.append(");\n");
		} else if ("google.protobuf.Struct".equals(fullName)) {
			sb.append(indent).append(prefix)
					.append("(io.suboptimal.buffjson.internal.WellKnownTypes.readStruct(reader)").append(closeSuffix)
					.append(");\n");
		} else if ("google.protobuf.Value".equals(fullName)) {
			sb.append(indent).append(prefix)
					.append("(io.suboptimal.buffjson.internal.WellKnownTypes.readJsonValue(reader)").append(closeSuffix)
					.append(");\n");
		} else if ("google.protobuf.ListValue".equals(fullName)) {
			sb.append(indent).append(prefix)
					.append("(io.suboptimal.buffjson.internal.WellKnownTypes.readListValue(reader)").append(closeSuffix)
					.append(");\n");
		} else if ("google.protobuf.Empty".equals(fullName)) {
			sb.append(indent).append("reader.nextIfObjectStart();\n");
			sb.append(indent)
					.append("while (!reader.nextIfObjectEnd()) { reader.readFieldName(); reader.skipValue(); }\n");
			sb.append(indent).append(prefix).append("(com.google.protobuf.Empty.getDefaultInstance()")
					.append(closeSuffix).append(");\n");
		} else if (WELL_KNOWN_TYPES.contains(fullName)) {
			String msgJavaClass = protoToJavaClass.get(fullName);
			sb.append(indent).append(prefix).append("((").append(msgJavaClass)
					.append(") io.suboptimal.buffjson.internal.WellKnownTypes.readWkt(reader, ").append(msgJavaClass)
					.append(".getDescriptor(), msgReader)").append(closeSuffix).append(");\n");
		} else {
			String decoderClass = protoToDecoderClass.get(fullName);
			if (decoderClass != null) {
				sb.append(indent).append(prefix).append("(").append(decoderClass)
						.append(".INSTANCE.readMessage(reader, msgReader)").append(closeSuffix).append(");\n");
			} else {
				String msgJavaClass = protoToJavaClass.get(fullName);
				sb.append(indent).append(prefix).append("(msgReader.readMessage(reader, ").append(msgJavaClass)
						.append(".getDescriptor(), ").append(msgJavaClass).append(".getDefaultInstance())")
						.append(closeSuffix).append(");\n");
			}
		}
	}

	// --- Helpers ---

	private static String mapKeyExpr(FieldDescriptor keyFd) {
		return switch (keyFd.getJavaType()) {
			case STRING -> "keyStr";
			case INT -> {
				var type = keyFd.getType();
				if (type == FieldDescriptor.Type.UINT32 || type == FieldDescriptor.Type.FIXED32)
					yield "io.suboptimal.buffjson.internal.FieldReader.parseUnsignedIntKey(reader, keyStr)";
				yield "io.suboptimal.buffjson.internal.FieldReader.parseIntKey(reader, keyStr)";
			}
			case LONG -> {
				var type = keyFd.getType();
				if (type == FieldDescriptor.Type.UINT64 || type == FieldDescriptor.Type.FIXED64)
					yield "io.suboptimal.buffjson.internal.FieldReader.parseUnsignedLongKey(reader, keyStr)";
				yield "io.suboptimal.buffjson.internal.FieldReader.parseLongKey(reader, keyStr)";
			}
			case BOOLEAN -> "io.suboptimal.buffjson.internal.FieldReader.parseBoolKey(reader, keyStr)";
			default -> throw new IllegalArgumentException("Unsupported map key type: " + keyFd.getJavaType());
		};
	}

	private static boolean isValueField(FieldDescriptor fd) {
		return fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE
				&& "google.protobuf.Value".equals(fd.getMessageType().getFullName());
	}

	private static boolean isNullValueField(FieldDescriptor fd) {
		return fd.getJavaType() == FieldDescriptor.JavaType.ENUM
				&& "google.protobuf.NullValue".equals(fd.getEnumType().getFullName());
	}
}
