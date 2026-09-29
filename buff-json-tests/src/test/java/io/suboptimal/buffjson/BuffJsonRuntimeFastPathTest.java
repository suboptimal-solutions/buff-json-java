package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import com.google.protobuf.Message;
import com.google.protobuf_test_messages.proto3.TestMessagesProto3.TestAllTypesProto3;

import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.internal.codegen.RuntimeCodegen;
import io.suboptimal.buffjson.proto.*;

/**
 * The same checks as {@link BuffJsonFastPathTest}, on readers generated at run
 * time by the Class-File API (Java 24+) instead of the plugin's: every message
 * of the graph is generated, none of the plugin's decoders is called. Both are
 * the same template, so on top of the shared checks the two are compared with
 * each other.
 */
class BuffJsonRuntimeFastPathTest extends FastPathTestBase {

	private static final BuffJsonDecoder FAST = BuffJson.decoder().setGeneratedDecoders(false).setRuntimeCodegen(true);

	@Override
	protected boolean available() {
		return RuntimeCodegen.available();
	}

	private static final BuffJsonDecoder GENERAL = BuffJson.decoder().setFastPath(false).setGeneratedDecoders(false)
			.setRuntimeCodegen(false);

	@Override
	protected BuffJsonDecoder fast() {
		return FAST;
	}

	@Override
	protected BuffJsonDecoder general() {
		return GENERAL;
	}

	@Override
	protected Message readFast(Message defaultInstance, byte[] json, boolean latin1) {
		return run(generated(defaultInstance), json, latin1);
	}

	static BuffJsonGeneratedDecoder<Message> generated(Message defaultInstance) {
		Class<?> type = defaultInstance.getClass();
		var decoder = RuntimeCodegen.fastDecoder(type, false);
		assertNotNull(decoder,
				"no generated decoder for " + type.getName() + ": " + RuntimeCodegen.failure(type, false));
		return decoder;
	}

	@Test
	void everyMessageTypeGetsAGeneratedDecoder() {
		for (Message type : List.of(TestAllScalars.getDefaultInstance(), TestRepeatedScalars.getDefaultInstance(),
				TestNesting.getDefaultInstance(), TestRecursive.getDefaultInstance(), TestOneof.getDefaultInstance(),
				TestMaps.getDefaultInstance(), TestWrappers.getDefaultInstance(), TestTimestamp.getDefaultInstance(),
				TestDuration.getDefaultInstance(), TestOptionalFields.getDefaultInstance(),
				TestCustomJsonName.getDefaultInstance(), TestEscapedJsonNames.getDefaultInstance(),
				TestDeprecatedFields.getDefaultInstance(), TestNameCollisions.getDefaultInstance(),
				TestNameLengths.getDefaultInstance(), TestAllTypesProto3.getDefaultInstance())) {
			assertNotNull(generated(type));
			assertTrue(generated(type).getClass().getName().endsWith("$$BuffJsonFastRt"));
		}
	}

	@Test
	void generatedAndPluginReadersAgreeOnEveryDocument() {
		// same template, so the same documents are accepted and give the same message
		long compared = 0;
		long accepted = 0;
		for (Message type : List.of(TestAllScalars.getDefaultInstance(), TestNesting.getDefaultInstance(),
				TestMaps.getDefaultInstance(), TestOneof.getDefaultInstance(), TestWrappers.getDefaultInstance(),
				TestTimestamp.getDefaultInstance(), TestOptionalFields.getDefaultInstance(),
				TestAllTypesProto3.getDefaultInstance())) {
			Random rng = new Random(0x51AB ^ type.getDescriptorForType().getFullName().hashCode());
			for (int i = 0; i < 150; i++) {
				Message message = RandomProtoMessages.generate(type, rng, 3,
						RandomProtoMessages.UNSUPPORTED_BY_FAST_PATH);
				byte[] original = BuffJson.encoder().encode(message).getBytes(StandardCharsets.UTF_8);
				for (int k = 0; k < 40; k++) {
					byte[] json = k == 0 ? original : mutateForComparison(original, rng);
					Message compiled = readFastCompiled(type, json, false);
					Message runtime = readFast(type, json, false);
					assertEquals(compiled, runtime, new String(json, StandardCharsets.ISO_8859_1));
					compared++;
					accepted += compiled != null ? 1 : 0;
				}
			}
		}
		assertTrue(compared > 40_000, "compared " + compared);
		assertTrue(accepted > 3_000, "accepted " + accepted);
	}

	private static final String STRUCTURAL = "\"\\,:{}[] tnfu0-+.eE1";

	private static byte[] mutateForComparison(byte[] json, Random rng) {
		byte[] copy = json.clone();
		int edits = 1 + rng.nextInt(3);
		for (int e = 0; e < edits && copy.length > 0; e++) {
			int at = rng.nextInt(copy.length);
			switch (rng.nextInt(4)) {
				case 0 -> copy[at] = (byte) rng.nextInt(256);
				case 1 -> copy[at] = (byte) STRUCTURAL.charAt(rng.nextInt(STRUCTURAL.length()));
				case 2 -> copy = java.util.Arrays.copyOf(copy, at);
				default -> {
					byte t = copy[at];
					copy[at] = copy[(at + 1) % copy.length];
					copy[(at + 1) % copy.length] = t;
				}
			}
		}
		return copy;
	}
}
