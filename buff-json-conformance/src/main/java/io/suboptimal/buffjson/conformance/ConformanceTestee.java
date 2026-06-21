package io.suboptimal.buffjson.conformance;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.BytesValue;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Duration;
import com.google.protobuf.Empty;
import com.google.protobuf.FieldMask;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.ListValue;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import com.google.protobuf.Value;
import com.google.protobuf.conformance.Conformance.ConformanceRequest;
import com.google.protobuf.conformance.Conformance.ConformanceRequest.PayloadCase;
import com.google.protobuf.conformance.Conformance.ConformanceResponse;
import com.google.protobuf.conformance.Conformance.FailureSet;
import com.google.protobuf.conformance.Conformance.WireFormat;
import com.google.protobuf_test_messages.proto3.TestMessagesProto3.TestAllTypesProto3;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonDecoder;
import io.suboptimal.buffjson.BuffJsonEncoder;

/**
 * Testee for the official protobuf conformance test suite
 * ({@code conformance_test_runner}).
 *
 * <p>
 * The runner spawns this program and drives it over stdin/stdout using a simple
 * framed protocol: each message is a little-endian {@code uint32} length prefix
 * followed by a serialized {@link ConformanceRequest} (request) or
 * {@link ConformanceResponse} (response). The loop runs until the runner closes
 * stdin.
 *
 * <p>
 * buff-json is a <b>proto3 JSON</b> codec, so this testee only exercises the
 * JSON portion of the suite:
 *
 * <ul>
 * <li>only {@code protobuf_test_messages.proto3.TestAllTypesProto3} is handled;
 * proto2 / editions message types are {@code skipped};</li>
 * <li>requests that involve no JSON at all (binary&rarr;binary round-trips,
 * which exercise protobuf-java's wire codec rather than buff-json) are
 * {@code skipped};</li>
 * <li>JSON is parsed/serialized with {@link BuffJson}; the protobuf side of a
 * JSON test is handled by protobuf-java;</li>
 * <li>text format and JSPB are {@code skipped}.</li>
 * </ul>
 *
 * <p>
 * Anything written to {@code stdout} other than framed responses would corrupt
 * the protocol, so all diagnostics go to {@code stderr}.
 */
public final class ConformanceTestee {

	/** Full proto name of the only message type this testee handles. */
	private static final String PROTO3_ALL_TYPES = "protobuf_test_messages.proto3.TestAllTypesProto3";

	/** Full proto name of the runner's initial failure-set probe. */
	private static final String FAILURE_SET = "conformance.FailureSet";

	private static final TypeRegistry TYPE_REGISTRY = buildTypeRegistry();

	/**
	 * Which buff-json encoding/decoding path to exercise, from the {@code
	 * BUFFJSON_PATH} env var (default {@code codegen}):
	 * <ul>
	 * <li>{@code codegen} — generated per-message encoders/decoders;</li>
	 * <li>{@code runtime} — generated codecs off: typed-accessor encode +
	 * reflection decode;</li>
	 * <li>{@code reflection} — generated + typed off: pure-reflection encode +
	 * reflection decode.</li>
	 * </ul>
	 */
	private static final String PATH_MODE = pathMode();
	private static final BuffJsonEncoder ENCODER = configureEncoder();
	private static final BuffJsonDecoder DECODER = configureDecoder();

	private ConformanceTestee() {
	}

	private static String pathMode() {
		String mode = System.getenv("BUFFJSON_PATH");
		return mode == null || mode.isBlank() ? "codegen" : mode.trim().toLowerCase(java.util.Locale.ROOT);
	}

	private static BuffJsonEncoder configureEncoder() {
		BuffJsonEncoder encoder = BuffJson.encoder().setTypeRegistry(TYPE_REGISTRY);
		return switch (PATH_MODE) {
			case "codegen" -> encoder;
			case "runtime" -> encoder.setGeneratedEncoders(false);
			case "reflection" -> encoder.setGeneratedEncoders(false).setTypedAccessors(false);
			default -> throw new IllegalArgumentException("Unknown BUFFJSON_PATH: " + PATH_MODE);
		};
	}

	private static BuffJsonDecoder configureDecoder() {
		BuffJsonDecoder decoder = BuffJson.decoder().setTypeRegistry(TYPE_REGISTRY);
		// The decoder has no typed-accessor tier — both runtime and reflection use the
		// reflection decode path; only codegen differs.
		return "codegen".equals(PATH_MODE) ? decoder : decoder.setGeneratedDecoders(false);
	}

	public static void main(String[] args) throws IOException {
		// Raw fd streams — never System.out, which other code might scribble on.
		InputStream in = new BufferedInputStream(new FileInputStream(FileDescriptor.in));
		OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out));

		long handled = 0;
		while (true) {
			Integer length = readLengthPrefix(in);
			if (length == null) {
				break; // clean EOF — runner finished
			}
			byte[] requestBytes = readFully(in, length);
			ConformanceRequest request = ConformanceRequest.parseFrom(requestBytes);
			ConformanceResponse response = handle(request);
			byte[] responseBytes = response.toByteArray();
			writeLengthPrefix(out, responseBytes.length);
			out.write(responseBytes);
			out.flush();
			handled++;
		}
		System.err.println("buff-json conformance testee [" + PATH_MODE + "]: handled " + handled + " request(s)");
	}

	static ConformanceResponse handle(ConformanceRequest request) {
		// The runner's first request asks the testee for its own known-failure set;
		// we declare none and rely on the external failure_list.txt instead.
		if (FAILURE_SET.equals(request.getMessageType())) {
			return ConformanceResponse.newBuilder().setProtobufPayload(FailureSet.getDefaultInstance().toByteString())
					.build();
		}

		// Only proto3 TestAllTypesProto3 is in scope for a proto3 JSON codec.
		if (!PROTO3_ALL_TYPES.equals(request.getMessageType())) {
			return skipped("unsupported message type: " + request.getMessageType());
		}

		WireFormat outputFormat = request.getRequestedOutputFormat();
		PayloadCase payload = request.getPayloadCase();
		boolean inputIsJson = payload == PayloadCase.JSON_PAYLOAD;
		boolean outputIsJson = outputFormat == WireFormat.JSON;

		// Limit to the JSON suite: a request touching no JSON only exercises
		// protobuf-java's binary codec, not buff-json.
		if (!inputIsJson && !outputIsJson) {
			return skipped("not a JSON test");
		}

		// ---- parse the payload into a message ----
		TestAllTypesProto3 message;
		try {
			if (payload == PayloadCase.PROTOBUF_PAYLOAD) {
				message = TestAllTypesProto3.parseFrom(request.getProtobufPayload());
			} else if (payload == PayloadCase.JSON_PAYLOAD) {
				TestAllTypesProto3 parsed = DECODER.decode(request.getJsonPayload(), TestAllTypesProto3.class);
				message = parsed != null ? parsed : TestAllTypesProto3.getDefaultInstance();
			} else if (payload == PayloadCase.TEXT_PAYLOAD || payload == PayloadCase.JSPB_PAYLOAD) {
				return skipped("unsupported input format: " + payload);
			} else {
				return runtimeError("request has no payload");
			}
		} catch (Throwable t) {
			// Many conformance cases feed intentionally invalid input; a parse failure
			// here is an expected, valid outcome — not a crash.
			return ConformanceResponse.newBuilder().setParseError(describe(t)).build();
		}

		// ---- serialize the message to the requested format ----
		try {
			if (outputFormat == WireFormat.PROTOBUF) {
				return ConformanceResponse.newBuilder().setProtobufPayload(message.toByteString()).build();
			} else if (outputFormat == WireFormat.JSON) {
				return ConformanceResponse.newBuilder().setJsonPayload(ENCODER.encode(message)).build();
			} else if (outputFormat == WireFormat.TEXT_FORMAT || outputFormat == WireFormat.JSPB) {
				return skipped("unsupported output format: " + outputFormat);
			} else {
				return runtimeError("unknown output format: " + outputFormat);
			}
		} catch (Throwable t) {
			return ConformanceResponse.newBuilder().setSerializeError(describe(t)).build();
		}
	}

	private static ConformanceResponse skipped(String reason) {
		return ConformanceResponse.newBuilder().setSkipped(reason).build();
	}

	private static ConformanceResponse runtimeError(String reason) {
		return ConformanceResponse.newBuilder().setRuntimeError(reason).build();
	}

	private static String describe(Throwable t) {
		String message = t.getMessage();
		return message != null ? t.getClass().getSimpleName() + ": " + message : t.getClass().getSimpleName();
	}

	/**
	 * Registers the message types that may appear inside
	 * {@code google.protobuf.Any} payloads so buff-json can resolve {@code @type}
	 * URLs during JSON encode/decode.
	 */
	private static TypeRegistry buildTypeRegistry() {
		List<Descriptor> descriptors = List.of(TestAllTypesProto3.getDescriptor(), Any.getDescriptor(),
				Duration.getDescriptor(), Timestamp.getDescriptor(), FieldMask.getDescriptor(), Struct.getDescriptor(),
				Value.getDescriptor(), ListValue.getDescriptor(), Empty.getDescriptor(), BoolValue.getDescriptor(),
				Int32Value.getDescriptor(), Int64Value.getDescriptor(), UInt32Value.getDescriptor(),
				UInt64Value.getDescriptor(), FloatValue.getDescriptor(), DoubleValue.getDescriptor(),
				StringValue.getDescriptor(), BytesValue.getDescriptor());
		return TypeRegistry.newBuilder().add(descriptors).build();
	}

	// ---- framed I/O: little-endian uint32 length prefix ----

	/**
	 * Reads a 4-byte little-endian length prefix. Returns {@code null} on a clean
	 * EOF before any byte (runner finished), throws on a truncated prefix.
	 */
	private static Integer readLengthPrefix(InputStream in) throws IOException {
		int b0 = in.read();
		if (b0 == -1) {
			return null;
		}
		int b1 = in.read();
		int b2 = in.read();
		int b3 = in.read();
		if ((b1 | b2 | b3) < 0) {
			throw new EOFException("truncated length prefix");
		}
		return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
	}

	private static void writeLengthPrefix(OutputStream out, int length) throws IOException {
		out.write(length & 0xFF);
		out.write((length >>> 8) & 0xFF);
		out.write((length >>> 16) & 0xFF);
		out.write((length >>> 24) & 0xFF);
	}

	private static byte[] readFully(InputStream in, int length) throws IOException {
		byte[] buffer = new byte[length];
		int offset = 0;
		while (offset < length) {
			int read = in.read(buffer, offset, length - offset);
			if (read == -1) {
				throw new EOFException("expected " + length + " bytes, got " + offset);
			}
			offset += read;
		}
		return buffer;
	}
}
