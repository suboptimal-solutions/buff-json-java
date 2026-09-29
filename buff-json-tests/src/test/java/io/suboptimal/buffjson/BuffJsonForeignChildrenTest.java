package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import com.google.protobuf.Message;
import com.google.protobuf.SourceContext;
import com.google.protobuf.util.JsonFormat;

import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.internal.FastInput;
import io.suboptimal.buffjson.internal.codegen.RuntimeCodegen;
import io.suboptimal.buffjson.proto.TestForeign;

/**
 * A message that holds messages of a class the plugin never generated code for
 * (here protobuf-java's {@code SourceContext}). The parent's reader has no
 * decoder class to name for them, so it looks a reader up when it meets one:
 * the message's own plugin-generated decoder if it has one, otherwise -- when
 * the decoder allows run-time generation and the JVM can do it -- one generated
 * on the spot. Without either the parent gives up on that document, as before,
 * and the general decoder answers. In every case the message decoded is the
 * same.
 */
class BuffJsonForeignChildrenTest {

	private static final JsonFormat.Printer COMPACT = JsonFormat.printer().omittingInsignificantWhitespace();

	private static final List<BuffJsonDecoder> DECODERS = List.of(BuffJson.decoder(),
			BuffJson.decoder().setRuntimeCodegen(true), BuffJson.decoder().setFastPath(false),
			BuffJson.decoder().setGeneratedDecoders(false),
			BuffJson.decoder().setGeneratedDecoders(false).setRuntimeCodegen(true));

	private static TestForeign sample() {
		return TestForeign.newBuilder().setLabel("x").setContext(SourceContext.newBuilder().setFileName("a.proto"))
				.addContexts(SourceContext.newBuilder().setFileName("b.proto"))
				.addContexts(SourceContext.newBuilder().setFileName("c \u00e9 \\ \"q\""))
				.putByName("k1", SourceContext.newBuilder().setFileName("d.proto").build())
				.putByName("k2", SourceContext.getDefaultInstance()).build();
	}

	@Test
	void everyDecoderReadsForeignChildren() throws Exception {
		TestForeign message = sample();
		String json = COMPACT.print(message);
		for (BuffJsonDecoder decoder : DECODERS) {
			assertEquals(message, decoder.decode(json, TestForeign.class));
			assertEquals(message, decoder.decode(json.getBytes(StandardCharsets.UTF_8), TestForeign.class));
			// and respelled: whitespace, the proto name of a member, an escaped name
			String respelled = "{ \"by_name\" : {\"k1\":{\"file_name\":\"d.proto\"},\"k2\":{}},\"\\u0063ontext\":{\"fileName\":\"a.proto\"},"
					+ "\"contexts\":[{\"fileName\":\"b.proto\"},{\"fileName\":\"c \u00e9 \\\\ \\\"q\\\"\"}],\"label\":\"x\"}";
			assertEquals(message, decoder.decode(respelled, TestForeign.class));
		}
	}

	private static Message readFast(byte[] json, boolean allowRuntimeCodegen) {
		BuffJsonGeneratedDecoder<?> decoder = ((BuffJsonCodecHolder) TestForeign.getDefaultInstance())
				.buffJsonDecoder();
		try {
			FastInput in = new FastInput(json, 0, json.length).allowRuntimeCodegen(allowRuntimeCodegen);
			Message message = decoder.readFast(in);
			return in.finished() ? message : null;
		} catch (FastInput.Bail | IndexOutOfBoundsException bail) {
			return null;
		}
	}

	@Test
	void theFastPathReadsForeignChildrenOnlyWhereARunTimeReaderIsAllowedAndPossible() throws Exception {
		TestForeign message = sample();
		byte[] json = COMPACT.print(message).getBytes(StandardCharsets.UTF_8);
		assertNull(readFast(json, false), "nothing to read a SourceContext with: the parent gives up");
		if (RuntimeCodegen.available()) {
			assertEquals(message, readFast(json, true));
		} else {
			assertNull(readFast(json, true));
		}
		// a document without a foreign child never needs a reader for one
		byte[] plain = "{\"label\":\"only\"}".getBytes(StandardCharsets.UTF_8);
		assertEquals(TestForeign.newBuilder().setLabel("only").build(), readFast(plain, false));
	}

	private record Outcome(Message message, String failure) {
		static Outcome of(BuffJsonDecoder decoder, byte[] json) {
			try {
				return new Outcome(decoder.decode(json, TestForeign.class), null);
			} catch (RuntimeException e) {
				return new Outcome(null, e.getClass().getName());
			}
		}
	}

	@Test
	void randomAndDamagedDocumentsGiveTheGeneralResultInEveryTier() throws Exception {
		// each tier is compared with its own general decoder: the runtime decoder is
		// more
		// lenient than the plugin's about a few things (a null list element) that the
		// fast path must not change
		BuffJsonDecoder pluginGeneral = BuffJson.decoder().setFastPath(false);
		BuffJsonDecoder typedGeneral = BuffJson.decoder().setGeneratedDecoders(false).setRuntimeCodegen(false);
		List<BuffJsonDecoder> pluginTier = List.of(BuffJson.decoder(), BuffJson.decoder().setRuntimeCodegen(true));
		List<BuffJsonDecoder> typedTier = List
				.of(BuffJson.decoder().setGeneratedDecoders(false).setRuntimeCodegen(true));
		String structural = "\"\\,:{}[] tnfu0-+.eE1x";
		Random rng = new Random(0xF0E1);
		long accepted = 0;
		for (int i = 0; i < 300; i++) {
			Message message = RandomProtoMessages.generate(TestForeign.getDefaultInstance(), rng, 3,
					java.util.Set.of());
			byte[] original = COMPACT.print(message).getBytes(StandardCharsets.UTF_8);
			for (int k = 0; k < 20; k++) {
				byte[] json = original.clone();
				if (k > 0 && json.length > 0) {
					json[rng.nextInt(json.length)] = (byte) structural.charAt(rng.nextInt(structural.length()));
				}
				String text = new String(json, StandardCharsets.ISO_8859_1);
				Outcome expected = Outcome.of(pluginGeneral, json);
				for (BuffJsonDecoder decoder : pluginTier) {
					assertEquals(expected, Outcome.of(decoder, json), text);
				}
				Outcome expectedTyped = Outcome.of(typedGeneral, json);
				for (BuffJsonDecoder decoder : typedTier) {
					assertEquals(expectedTyped, Outcome.of(decoder, json), text);
				}
				// whenever the parent's reader itself accepts a document, the general decoder
				// agrees
				Message direct = readFast(json, true);
				if (direct != null) {
					accepted++;
					assertNull(expected.failure(), text);
					assertEquals(expected.message(), direct, text);
				}
			}
		}
		assertTrue(accepted > (RuntimeCodegen.available() ? 1000 : -1), "accepted " + accepted);
	}
}
