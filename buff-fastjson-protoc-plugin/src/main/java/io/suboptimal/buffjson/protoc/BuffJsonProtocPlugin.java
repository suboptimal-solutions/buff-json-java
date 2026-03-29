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
 * Protoc plugin that generates optimized JSON encoder classes for protobuf
 * messages. Generated encoders use typed accessors (e.g., {@code getId()})
 * instead of reflection-style {@code getField()}, eliminating boxing and
 * runtime type dispatch.
 *
 * <p>
 * Invoked by protoc via stdin/stdout protocol. Generates one
 * {@code *JsonEncoder} class per message type, plus a {@code META-INF/services}
 * file for ServiceLoader discovery.
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
			Map<String, FileDescriptor> fileDescriptors = buildFileDescriptors(request);
			Map<String, String> protoToJavaClass = buildClassNameMap(fileDescriptors);
			Set<String> filesToGenerate = new HashSet<>(request.getFileToGenerateList());

			// Pre-compute encoder class names so generated encoders can reference each
			// other directly (bypassing the registry for nested message fields)
			Map<String, String> protoToEncoderClass = new HashMap<>();
			for (FileDescriptor fileDesc : fileDescriptors.values()) {
				if (!filesToGenerate.contains(fileDesc.getName()))
					continue;
				String javaPackage = getJavaPackage(fileDesc);
				for (Descriptor msgDesc : fileDesc.getMessageTypes()) {
					collectEncoderNames(msgDesc, javaPackage, protoToEncoderClass);
				}
			}

			List<String> encoderClassNames = new ArrayList<>();

			for (FileDescriptor fileDesc : fileDescriptors.values()) {
				if (!filesToGenerate.contains(fileDesc.getName()))
					continue;

				String javaPackage = getJavaPackage(fileDesc);

				for (Descriptor msgDesc : fileDesc.getMessageTypes()) {
					generateEncoders(response, msgDesc, javaPackage, protoToJavaClass, protoToEncoderClass,
							encoderClassNames);
				}
			}

			if (!encoderClassNames.isEmpty()) {
				response.addFile(CodeGeneratorResponse.File.newBuilder()
						.setName("META-INF/services/io.suboptimal.buffjson.GeneratedEncoder")
						.setContent(String.join("\n", encoderClassNames) + "\n").build());
			}
		} catch (Exception e) {
			response.setError(e.getMessage());
		}

		response.build().writeTo(System.out);
	}

	private static void collectEncoderNames(Descriptor msgDesc, String javaPackage,
			Map<String, String> protoToEncoderClass) {
		if (msgDesc.getOptions().getMapEntry())
			return;
		if (WELL_KNOWN_TYPES.contains(msgDesc.getFullName()))
			return;
		protoToEncoderClass.put(msgDesc.getFullName(), javaPackage + "." + flatName(msgDesc) + "JsonEncoder");
		for (Descriptor nested : msgDesc.getNestedTypes()) {
			collectEncoderNames(nested, javaPackage, protoToEncoderClass);
		}
	}

	private static void generateEncoders(CodeGeneratorResponse.Builder response, Descriptor msgDesc, String javaPackage,
			Map<String, String> protoToJavaClass, Map<String, String> protoToEncoderClass,
			List<String> encoderClassNames) {

		if (msgDesc.getOptions().getMapEntry())
			return;
		if (WELL_KNOWN_TYPES.contains(msgDesc.getFullName()))
			return;

		String messageClassName = protoToJavaClass.get(msgDesc.getFullName());
		String encoderSimpleName = flatName(msgDesc) + "JsonEncoder";
		String encoderFullName = javaPackage + "." + encoderSimpleName;

		String source = EncoderGenerator.generate(msgDesc, javaPackage, encoderSimpleName, messageClassName,
				protoToJavaClass, protoToEncoderClass);

		String filePath = javaPackage.replace('.', '/') + "/" + encoderSimpleName + ".java";
		response.addFile(CodeGeneratorResponse.File.newBuilder().setName(filePath).setContent(source).build());
		encoderClassNames.add(encoderFullName);

		for (Descriptor nested : msgDesc.getNestedTypes()) {
			generateEncoders(response, nested, javaPackage, protoToJavaClass, protoToEncoderClass, encoderClassNames);
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
