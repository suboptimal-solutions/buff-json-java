package io.suboptimal.buffjson.protoc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorRequest;
import com.google.protobuf.compiler.PluginProtos.CodeGeneratorResponse;

/**
 * Protoc plugin that generates optimized JSON encoder and decoder classes for
 * protobuf messages. Generated encoders use typed accessors (e.g.,
 * {@code getId()}) instead of reflection-style {@code getField()}, eliminating
 * boxing and runtime type dispatch.
 *
 * <p>
 * Invoked by protoc via stdin/stdout protocol. Generates one
 * {@code *JsonEncoder} and one {@code *JsonDecoder} class per message type,
 * plus insertion points that inject {@code BuffJsonCodecHolder} into the
 * generated protobuf message classes.
 */
public final class BuffJsonProtocPlugin {

	/**
	 * WKTs with special JSON representations handled by
	 * {@code WellKnownTypes.write()}. Must match the set in the core library. Note:
	 * {@code google.protobuf.Empty} is NOT included — it serializes as a regular
	 * empty message {@code {}}.
	 */
	static final Set<String> WELL_KNOWN_TYPES = Set.of("google.protobuf.Any", "google.protobuf.Timestamp",
			"google.protobuf.Duration", "google.protobuf.FieldMask", "google.protobuf.Struct", "google.protobuf.Value",
			"google.protobuf.ListValue", "google.protobuf.DoubleValue", "google.protobuf.FloatValue",
			"google.protobuf.Int64Value", "google.protobuf.UInt64Value", "google.protobuf.Int32Value",
			"google.protobuf.UInt32Value", "google.protobuf.BoolValue", "google.protobuf.StringValue",
			"google.protobuf.BytesValue");

	public static void main(String[] args) throws Exception {
		CodeGeneratorRequest request = CodeGeneratorRequest.parseFrom(System.in);

		CodeGeneratorResponse.Builder response = CodeGeneratorResponse.newBuilder();
		response.setSupportedFeatures(CodeGeneratorResponse.Feature.FEATURE_PROTO3_OPTIONAL_VALUE);

		try {
			generate(request, response);
		} catch (Exception e) {
			response.setError(e.getMessage());
		}

		response.build().writeTo(System.out);
	}

	/**
	 * Generates JSON encoder, decoder, and comment classes for the given protoc
	 * request, adding all output files to the provided response builder.
	 *
	 * <p>
	 * This method can be called directly to compose buff-json code generation
	 * within another protoc plugin. Exceptions are propagated to the caller.
	 */
	public static void generate(CodeGeneratorRequest request, CodeGeneratorResponse.Builder response) throws Exception {
		Map<String, FileDescriptor> fileDescriptors = buildFileDescriptors(request);
		Map<String, String> protoToJavaClass = buildClassNameMap(fileDescriptors);
		Set<String> filesToGenerate = new HashSet<>(request.getFileToGenerateList());

		// Pre-compute encoder and decoder class names so generated code can
		// reference each other directly (bypassing the registry)
		Map<String, String> protoToEncoderClass = new HashMap<>();
		Map<String, String> protoToDecoderClass = new HashMap<>();
		for (FileDescriptor fileDesc : fileDescriptors.values()) {
			if (!filesToGenerate.contains(fileDesc.getName()))
				continue;
			String javaPackage = getJavaPackage(fileDesc);
			for (Descriptor msgDesc : fileDesc.getMessageTypes()) {
				collectCodegenNames(msgDesc, javaPackage, protoToEncoderClass, "JsonEncoder");
				collectCodegenNames(msgDesc, javaPackage, protoToDecoderClass, "JsonDecoder");
			}
		}

		List<String> commentClassNames = new ArrayList<>();

		for (FileDescriptor fileDesc : fileDescriptors.values()) {
			if (!filesToGenerate.contains(fileDesc.getName()))
				continue;

			String javaPackage = getJavaPackage(fileDesc);

			for (Descriptor msgDesc : fileDesc.getMessageTypes()) {
				generateCodegenClasses(response, msgDesc, javaPackage, protoToJavaClass, protoToEncoderClass,
						"JsonEncoder", EncoderGenerator::generate);
				generateCodegenClasses(response, msgDesc, javaPackage, protoToJavaClass, protoToDecoderClass,
						"JsonDecoder", DecoderGenerator::generate);
				generateInsertionPoints(response, msgDesc, fileDesc, javaPackage, protoToEncoderClass,
						protoToDecoderClass);
			}

			// Generate comment provider per proto file
			FileDescriptorProto fdp = fileDesc.toProto();
			String commentClassName = getOuterClassName(fileDesc) + "Comments";
			String commentSource = CommentGenerator.generate(fdp, javaPackage, commentClassName);
			if (commentSource != null) {
				String commentFullName = javaPackage + "." + commentClassName;
				String commentFilePath = javaPackage.replace('.', '/') + "/" + commentClassName + ".java";
				response.addFile(CodeGeneratorResponse.File.newBuilder().setName(commentFilePath)
						.setContent(commentSource).build());
				commentClassNames.add(commentFullName);
			}
		}

		if (!commentClassNames.isEmpty()) {
			response.addFile(CodeGeneratorResponse.File.newBuilder()
					.setName("META-INF/services/io.suboptimal.buffjson.BuffJsonGeneratedComments")
					.setContent(String.join("\n", commentClassNames) + "\n").build());
		}
	}

	private static void collectCodegenNames(Descriptor msgDesc, String javaPackage, Map<String, String> out,
			String suffix) {
		if (msgDesc.getOptions().getMapEntry())
			return;
		if (WELL_KNOWN_TYPES.contains(msgDesc.getFullName()))
			return;
		out.put(msgDesc.getFullName(), javaPackage + "." + flatName(msgDesc) + suffix);
		for (Descriptor nested : msgDesc.getNestedTypes()) {
			collectCodegenNames(nested, javaPackage, out, suffix);
		}
	}

	@FunctionalInterface
	private interface CodeGenerator {
		String generate(Descriptor msgDesc, String javaPackage, String simpleName, String messageClassName,
				Map<String, String> protoToJavaClass, Map<String, String> protoToCodegenClass);
	}

	private static void generateCodegenClasses(CodeGeneratorResponse.Builder response, Descriptor msgDesc,
			String javaPackage, Map<String, String> protoToJavaClass, Map<String, String> protoToCodegenClass,
			String suffix, CodeGenerator generator) {

		if (msgDesc.getOptions().getMapEntry())
			return;
		if (WELL_KNOWN_TYPES.contains(msgDesc.getFullName()))
			return;

		String messageClassName = protoToJavaClass.get(msgDesc.getFullName());
		String simpleName = flatName(msgDesc) + suffix;

		String source = generator.generate(msgDesc, javaPackage, simpleName, messageClassName, protoToJavaClass,
				protoToCodegenClass);

		String filePath = javaPackage.replace('.', '/') + "/" + simpleName + ".java";
		response.addFile(CodeGeneratorResponse.File.newBuilder().setName(filePath).setContent(source).build());

		for (Descriptor nested : msgDesc.getNestedTypes()) {
			generateCodegenClasses(response, nested, javaPackage, protoToJavaClass, protoToCodegenClass, suffix,
					generator);
		}
	}

	/**
	 * Generates protoc insertion point files for the given message descriptor and
	 * its nested types. For each message, two insertion points are emitted:
	 * <ul>
	 * <li>{@code message_implements} — adds {@code BuffJsonCodecHolder} to the
	 * message's implements clause
	 * <li>{@code class_scope} — adds {@code buffJsonEncoder()} and
	 * {@code buffJsonDecoder()} method implementations
	 * </ul>
	 */
	private static void generateInsertionPoints(CodeGeneratorResponse.Builder response, Descriptor msgDesc,
			FileDescriptor fileDesc, String javaPackage, Map<String, String> protoToEncoderClass,
			Map<String, String> protoToDecoderClass) {

		if (msgDesc.getOptions().getMapEntry())
			return;
		if (WELL_KNOWN_TYPES.contains(msgDesc.getFullName()))
			return;

		String encoderClass = protoToEncoderClass.get(msgDesc.getFullName());
		String decoderClass = protoToDecoderClass.get(msgDesc.getFullName());
		if (encoderClass == null && decoderClass == null)
			return;

		String protoFilePath = insertionPointFilePath(msgDesc, fileDesc, javaPackage);
		String fullName = msgDesc.getFullName();

		// message_implements insertion point — add BuffJsonCodecHolder interface
		response.addFile(CodeGeneratorResponse.File.newBuilder().setName(protoFilePath)
				.setInsertionPoint("message_implements:" + fullName)
				.setContent("io.suboptimal.buffjson.BuffJsonCodecHolder,\n").build());

		// class_scope insertion point — add method implementations
		StringBuilder body = new StringBuilder();
		if (encoderClass != null) {
			body.append(
					"@Override public io.suboptimal.buffjson.BuffJsonGeneratedEncoder<?> buffJsonEncoder() { return ")
					.append(encoderClass).append(".INSTANCE; }\n");
		}
		if (decoderClass != null) {
			body.append(
					"@Override public io.suboptimal.buffjson.BuffJsonGeneratedDecoder<?> buffJsonDecoder() { return ")
					.append(decoderClass).append(".INSTANCE; }\n");
		}
		response.addFile(CodeGeneratorResponse.File.newBuilder().setName(protoFilePath)
				.setInsertionPoint("class_scope:" + fullName).setContent(body.toString()).build());

		for (Descriptor nested : msgDesc.getNestedTypes()) {
			generateInsertionPoints(response, nested, fileDesc, javaPackage, protoToEncoderClass, protoToDecoderClass);
		}
	}

	/**
	 * Computes the Java source file path for a message's insertion point. For
	 * {@code java_multiple_files = true}, this is the top-level message's own file.
	 * Otherwise, it's the outer class file.
	 */
	private static String insertionPointFilePath(Descriptor msgDesc, FileDescriptor fileDesc, String javaPackage) {
		boolean multipleFiles = fileDesc.getOptions().getJavaMultipleFiles();
		String packagePath = javaPackage.replace('.', '/');
		if (multipleFiles) {
			// Navigate to the top-level message (for nested types)
			Descriptor topLevel = msgDesc;
			while (topLevel.getContainingType() != null) {
				topLevel = topLevel.getContainingType();
			}
			return packagePath + "/" + topLevel.getName() + ".java";
		} else {
			return packagePath + "/" + getOuterClassName(fileDesc) + ".java";
		}
	}

	/**
	 * Flattened name for nested messages: Outer.Inner becomes Outer_Inner.
	 */
	private static String flatName(Descriptor desc) {
		if (desc.getContainingType() == null) {
			return desc.getName();
		}
		return flatName(desc.getContainingType()) + "_" + desc.getName();
	}

	private static Map<String, FileDescriptor> buildFileDescriptors(CodeGeneratorRequest request)
			throws DescriptorValidationException {

		Map<String, FileDescriptorProto> protoByName = new HashMap<>();
		for (FileDescriptorProto fdp : request.getProtoFileList()) {
			protoByName.put(fdp.getName(), fdp);
		}

		Map<String, FileDescriptor> built = new HashMap<>();
		for (FileDescriptorProto fdp : request.getProtoFileList()) {
			buildFileDescriptor(fdp, protoByName, built);
		}
		return built;
	}

	private static FileDescriptor buildFileDescriptor(FileDescriptorProto fdp,
			Map<String, FileDescriptorProto> protoByName, Map<String, FileDescriptor> built)
			throws DescriptorValidationException {

		FileDescriptor existing = built.get(fdp.getName());
		if (existing != null)
			return existing;

		FileDescriptor[] deps = new FileDescriptor[fdp.getDependencyCount()];
		for (int i = 0; i < fdp.getDependencyCount(); i++) {
			String depName = fdp.getDependency(i);
			deps[i] = buildFileDescriptor(protoByName.get(depName), protoByName, built);
		}

		FileDescriptor fd = FileDescriptor.buildFrom(fdp, deps);
		built.put(fdp.getName(), fd);
		return fd;
	}

	/**
	 * Builds a map from proto full name to Java class name for all messages and
	 * enums across all files.
	 */
	static Map<String, String> buildClassNameMap(Map<String, FileDescriptor> fileDescriptors) {
		Map<String, String> map = new HashMap<>();
		for (FileDescriptor fd : fileDescriptors.values()) {
			String javaPackage = getJavaPackage(fd);
			boolean multipleFiles = fd.getOptions().getJavaMultipleFiles();
			String outerClassName = multipleFiles ? null : getOuterClassName(fd);

			for (Descriptor msg : fd.getMessageTypes()) {
				String prefix = multipleFiles ? javaPackage : javaPackage + "." + outerClassName;
				addMessageToMap(map, msg, prefix);
			}
			for (EnumDescriptor enumDesc : fd.getEnumTypes()) {
				String prefix = multipleFiles ? javaPackage : javaPackage + "." + outerClassName;
				map.put(enumDesc.getFullName(), prefix + "." + enumDesc.getName());
			}
		}
		return map;
	}

	private static void addMessageToMap(Map<String, String> map, Descriptor msg, String parentJavaName) {
		String javaName = parentJavaName + "." + msg.getName();
		map.put(msg.getFullName(), javaName);
		for (Descriptor nested : msg.getNestedTypes()) {
			addMessageToMap(map, nested, javaName);
		}
		for (EnumDescriptor enumDesc : msg.getEnumTypes()) {
			map.put(enumDesc.getFullName(), javaName + "." + enumDesc.getName());
		}
	}

	private static String getJavaPackage(FileDescriptor fd) {
		if (fd.getOptions().hasJavaPackage()) {
			return fd.getOptions().getJavaPackage();
		}
		return fd.getPackage();
	}

	private static String getOuterClassName(FileDescriptor fd) {
		if (fd.getOptions().hasJavaOuterClassname()) {
			return fd.getOptions().getJavaOuterClassname();
		}
		// Derive from file name: foo_bar.proto → FooBar
		String name = fd.getName();
		int lastSlash = name.lastIndexOf('/');
		if (lastSlash >= 0)
			name = name.substring(lastSlash + 1);
		name = name.replace(".proto", "");
		return toCamelCase(name);
	}

	static String toCamelCase(String name) {
		StringBuilder sb = new StringBuilder();
		boolean capitalizeNext = true;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '_') {
				capitalizeNext = true;
			} else {
				sb.append(capitalizeNext ? Character.toUpperCase(c) : c);
				capitalizeNext = false;
			}
		}
		return sb.toString();
	}
}
