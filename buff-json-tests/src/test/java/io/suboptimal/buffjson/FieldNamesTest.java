package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.stream.IntStream;

import com.alibaba.fastjson2.JSONWriter;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.suboptimal.buffjson.internal.FieldNames;

/**
 * Locks down the word-packed field-name fast path.
 *
 * <p>
 * {@link FieldNames} derives the constants that fastjson2's
 * {@code writeName2Raw(long)} … {@code writeName16Raw(long, long)} family
 * consumes. That layout is an implementation detail of fastjson2 — it is not
 * documented, it differs per name length (lengths 8 and 16 skip the opening
 * quote), and a change to it would silently corrupt <b>every field name in
 * every message</b> rather than failing loudly. These tests assert the layout
 * directly, so a fastjson2 upgrade that moves it fails here instead of in the
 * conformance suite.
 */
class FieldNamesTest {

	private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

	static IntStream packableLengths() {
		return IntStream.rangeClosed(2, FieldNames.MAX_PACKED_LENGTH);
	}

	private static String nameOfLength(int n) {
		StringBuilder sb = new StringBuilder(n);
		for (int i = 0; i < n; i++) {
			sb.append((char) ('a' + i % 26));
		}
		return sb.toString();
	}

	/** Invokes the {@code writeName<n>Raw} overload matching the name's length. */
	private static void writePackedName(JSONWriter jw, String name) throws Throwable {
		int n = name.length();
		MethodType type;
		if (n == 9) {
			type = MethodType.methodType(void.class, long.class, int.class);
		} else if (n >= 10) {
			type = MethodType.methodType(void.class, long.class, long.class);
		} else {
			type = MethodType.methodType(void.class, long.class);
		}
		MethodHandle mh = LOOKUP.findVirtual(JSONWriter.class, "writeName" + n + "Raw", type);
		if (n == 9) {
			mh.invoke(jw, FieldNames.packedWord0(name), FieldNames.packedTailInt(name));
		} else if (n >= 10) {
			mh.invoke(jw, FieldNames.packedWord0(name), FieldNames.packedWord1(name));
		} else {
			mh.invoke(jw, FieldNames.packedWord0(name));
		}
	}

	@Nested
	class PackedLayout {

		@ParameterizedTest
		@MethodSource("io.suboptimal.buffjson.FieldNamesTest#packableLengths")
		void writesTheNameOnUtf8(int length) throws Throwable {
			String name = nameOfLength(length);
			try (JSONWriter jw = JSONWriter.ofUTF8()) {
				jw.startObject();
				writePackedName(jw, name);
				jw.writeInt32(1);
				jw.endObject();
				assertEquals("{\"" + name + "\":1}", jw.toString());
			}
		}

		@ParameterizedTest
		@MethodSource("io.suboptimal.buffjson.FieldNamesTest#packableLengths")
		void writesTheNameOnUtf16(int length) throws Throwable {
			String name = nameOfLength(length);
			try (JSONWriter jw = JSONWriter.of()) {
				jw.startObject();
				writePackedName(jw, name);
				jw.writeInt32(1);
				jw.endObject();
				assertEquals("{\"" + name + "\":1}", jw.toString());
			}
		}

		/**
		 * The comma between fields comes from {@code writeName<n>Raw} itself, so a
		 * second field must not lose or duplicate it.
		 */
		@ParameterizedTest
		@MethodSource("io.suboptimal.buffjson.FieldNamesTest#packableLengths")
		void separatesConsecutiveFields(int length) throws Throwable {
			String name = nameOfLength(length);
			try (JSONWriter jw = JSONWriter.ofUTF8()) {
				jw.startObject();
				writePackedName(jw, name);
				jw.writeInt32(1);
				writePackedName(jw, name);
				jw.writeInt32(2);
				jw.endObject();
				assertEquals("{\"" + name + "\":1,\"" + name + "\":2}", jw.toString());
			}
		}
	}

	@Nested
	class Packability {

		@ParameterizedTest
		@ValueSource(strings = {"", "a", "abcdefghijklmnopq", "naïve", "a\"b", "a\\b", "a\tb"})
		void rejectsNamesTheFastPathCannotEncode(String name) {
			assertFalse(FieldNames.isPackable(name), name);
			assertThrows(IllegalArgumentException.class, () -> FieldNames.packedWord0(name));
		}

		@Test
		void acceptsTypicalProtoJsonNames() {
			for (String name : new String[]{"id", "userId", "timestampMillis", "scoreValue", "a1"}) {
				assertTrue(FieldNames.isPackable(name), name);
			}
		}

		/**
		 * The array fallback must stay usable for the names the packed path rejects —
		 * it is what {@code FieldName} and the generated encoders fall back to.
		 */
		@Test
		void arrayFallbackEncodesTheSameText() {
			String name = "x";
			assertTrue(FieldNames.isRawWritable(name));
			assertFalse(FieldNames.isPackable(name));
			assertEquals("\"x\":", new String(FieldNames.nameWithColonChars(name)));
			assertArrayEquals("\"x\":".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
					FieldNames.nameWithColonBytes(name));
		}

		@ParameterizedTest
		@ValueSource(strings = {"naïve", "a\"b", "a\\b", "a\tb", "日本語"})
		void javaLiteralOfAnEscapableNameCompilesAndRoundTrips(String name) {
			assertFalse(FieldNames.isRawWritable(name), name);
			String literal = FieldNames.javaStringLiteral(name);
			assertTrue(literal.startsWith("\"") && literal.endsWith("\""), literal);
			// Every character in the literal body is source-safe ASCII.
			for (int i = 1; i < literal.length() - 1; i++) {
				char c = literal.charAt(i);
				assertTrue(c >= 0x20 && c <= 0x7e, literal + " has a raw char at " + i);
			}
		}
	}

	/**
	 * A {@code [json_name = "…"]} can be any string. Names that cannot be
	 * pre-encoded raw must be handed to fastjson2 so they get escaped and
	 * transcoded, not truncated by a {@code (byte) charAt(i)} cast.
	 */
	@Nested
	class EscapingFallback {

		@ParameterizedTest
		@ValueSource(strings = {"naïve", "a\"b", "日本語", "x"})
		void writesValidJsonOnBothEncodings(String name) {
			var fieldName = io.suboptimal.buffjson.internal.typed.FieldName.of(name);
			for (boolean utf8 : new boolean[]{true, false}) {
				try (JSONWriter jw = utf8 ? JSONWriter.ofUTF8() : JSONWriter.of()) {
					jw.startObject();
					fieldName.writeTo(jw);
					jw.writeInt32(1);
					jw.endObject();
					// Round-trip through a parser: the name must survive intact.
					assertEquals(name, com.alibaba.fastjson2.JSON.parseObject(jw.toString()).keySet().iterator().next(),
							"utf8=" + utf8 + " json=" + jw);
				}
			}
		}
	}
}
