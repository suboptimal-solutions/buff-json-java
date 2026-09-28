package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.alibaba.fastjson2.JSONReader;

import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.internal.FieldNameMatcher;

/**
 * {@link FieldNameMatcher} drives fastjson2's public {@code nextIfName4MatchN}
 * family, whose argument layout is undocumented. This test pins the layout for
 * every supported name length against the fastjson2 on the classpath, on every
 * reader implementation, and checks the two properties the decoders rely on:
 *
 * <ul>
 * <li><b>exact</b> - the matcher accepts only the byte sequence
 * {@code "name":}; every single-byte deviation, every truncation, every longer
 * or shorter name is rejected
 * <li><b>non-consuming on failure</b> - a rejected match leaves the reader
 * where it was, so the general {@code readFieldName()} route sees the same
 * input
 * </ul>
 */
class FieldNameMatcherTest {

	/**
	 * A tail that forces the byte readers onto their UTF-8 / UTF-16
	 * implementations.
	 */
	private static final String NON_ASCII_TAIL = ",\"z\":\"日本\"}";

	private record ReaderKind(String name, Function<String, JSONReader> open) {
	}

	private static final List<ReaderKind> KINDS = List.of(
			new ReaderKind("bytes (ASCII reader)", json -> JSONReader.of(json.getBytes(StandardCharsets.UTF_8))),
			new ReaderKind("String (Latin-1)", JSONReader::of),
			new ReaderKind("char[] (UTF-16 reader)", json -> JSONReader.of(json.toCharArray())));

	/**
	 * Same documents, but wide: the readers see non-ASCII / non-Latin-1 content.
	 */
	private static final List<ReaderKind> WIDE_KINDS = List.of(
			new ReaderKind("bytes (UTF-8 reader)", json -> JSONReader.of(json.getBytes(StandardCharsets.UTF_8))),
			new ReaderKind("String (UTF-16)", JSONReader::of),
			new ReaderKind("char[] (UTF-16 reader)", json -> JSONReader.of(json.toCharArray())));

	private static String nameOfLength(int length) {
		// Letters, digits and the punctuation JSON does not escape; never repeats a
		// position's byte in its neighbour so misaligned constants cannot cancel out.
		String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 !#$%&'()*+,-./:;<=>?@[]^_`{|}~";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < length; i++) {
			sb.append(alphabet.charAt((i * 7 + length) % alphabet.length()));
		}
		return sb.toString();
	}

	@Test
	void supportedNameLengthsAndCharacters() {
		assertNull(FieldNameMatcher.of(""));
		assertNull(FieldNameMatcher.of("a"));
		for (int length = 2; length <= 43; length++) {
			assertNotNull(FieldNameMatcher.of(nameOfLength(length)), "length " + length);
		}
		assertNull(FieldNameMatcher.of(nameOfLength(44)));
		assertNull(FieldNameMatcher.of(nameOfLength(100)));
		assertNull(FieldNameMatcher.of("has\"quote"));
		assertNull(FieldNameMatcher.of("has\\backslash"));
		assertNull(FieldNameMatcher.of("ctrl\u0001char"));
		assertNull(FieldNameMatcher.of("tab\there"));
		assertNull(FieldNameMatcher.of("non-ascii-é"));
		assertNull(FieldNameMatcher.of("日本"));
		assertNotNull(FieldNameMatcher.of("with space"));
		assertNotNull(FieldNameMatcher.of("p/q"));
	}

	@Test
	void everyLengthMatchesInEveryFormOnEveryReader() {
		for (int length = 2; length <= 43; length++) {
			String name = nameOfLength(length);
			FieldNameMatcher matcher = FieldNameMatcher.of(name);
			for (String body : List.of("{\"" + name + "\":7", "{\"" + name + "\": 7", "{\"" + name + "\":\n\t 7",
					"{ \n\"" + name + "\":7")) {
				for (String tail : List.of("}", NON_ASCII_TAIL)) {
					String json = body + tail;
					for (ReaderKind kind : tail.equals("}") ? KINDS : WIDE_KINDS) {
						String where = "length " + length + " on " + kind.name() + ": " + json;
						try (JSONReader r = kind.open().apply(json)) {
							assertTrue(r.nextIfObjectStart(), where);
							assertEquals(matcher.prefix(), r.getRawInt(), where + " (prefix)");
							assertTrue(matcher.match(r), where);
							assertEquals(7, r.readInt32Value(), where);
							if (tail.equals("}")) {
								assertTrue(r.nextIfObjectEnd(), where);
							} else {
								assertEquals("z", r.readFieldName(), where);
								assertEquals("日本", r.readString(), where);
								assertTrue(r.nextIfObjectEnd(), where);
							}
						}
					}
				}
			}
		}
	}

	@Test
	void matchesAfterAPrecedingMemberAndComma() {
		for (int length = 2; length <= 43; length++) {
			String name = nameOfLength(length);
			FieldNameMatcher matcher = FieldNameMatcher.of(name);
			String json = "{\"first\":1,  \"" + name + "\":2,\"last\":3}";
			for (ReaderKind kind : KINDS) {
				try (JSONReader r = kind.open().apply(json)) {
					assertTrue(r.nextIfObjectStart());
					assertEquals("first", r.readFieldName());
					assertEquals(1, r.readInt32Value());
					assertEquals(matcher.prefix(), r.getRawInt(), "length " + length + " on " + kind.name());
					assertTrue(matcher.match(r), "length " + length + " on " + kind.name());
					assertEquals(2, r.readInt32Value());
					assertEquals("last", r.readFieldName());
					assertEquals(3, r.readInt32Value());
					assertTrue(r.nextIfObjectEnd());
				}
			}
		}
	}

	@Test
	void everySingleByteDeviationIsRejectedWithoutConsuming() {
		for (int length = 2; length <= 43; length++) {
			String name = nameOfLength(length);
			FieldNameMatcher matcher = FieldNameMatcher.of(name);
			String pattern = "\"" + name + "\":";
			for (int i = 0; i < pattern.length(); i++) {
				char original = pattern.charAt(i);
				for (char replacement : new char[]{'Q', '\u007f', original == ' ' ? '!' : ' '}) {
					if (replacement == original) {
						continue;
					}
					String mutated = pattern.substring(0, i) + replacement + pattern.substring(i + 1);
					assertRejectedWithoutConsuming(matcher, "{" + mutated + "7}",
							"length " + length + ", byte " + i + " -> " + (int) replacement);
				}
			}
			// a name that merely starts with, or extends, the expected name
			assertRejectedWithoutConsuming(matcher, "{\"" + name + "x\":7}", "longer, length " + length);
			assertRejectedWithoutConsuming(matcher, "{\"" + name.substring(0, name.length() - 1) + "\":7}",
					"shorter, length " + length);
			// whitespace between the closing quote and the colon is not the fast spelling
			assertRejectedWithoutConsuming(matcher, "{\"" + name + "\" :7}", "space before colon, length " + length);
			// escaped spelling of the first byte
			assertRejectedWithoutConsuming(matcher,
					"{\"" + (name.charAt(0) == '\\' ? "" : String.format("\\u%04x", (int) name.charAt(0)))
							+ name.substring(1) + "\":7}",
					"escaped first byte, length " + length);
		}
	}

	@Test
	void truncatedInputIsRejectedWithoutErrors() {
		for (int length = 2; length <= 43; length++) {
			String name = nameOfLength(length);
			FieldNameMatcher matcher = FieldNameMatcher.of(name);
			String full = "{\"" + name + "\":7}";
			for (int cut = 1; cut < full.length(); cut++) {
				String json = full.substring(0, cut);
				boolean wholeNamePresent = cut >= 1 + name.length() + 3 + 1; // after the colon and at least one more
																				// byte
				for (ReaderKind kind : KINDS) {
					try (JSONReader r = kind.open().apply(json)) {
						r.nextIfObjectStart();
						if (r.current() != '"') {
							continue;
						}
						boolean matched;
						try {
							matched = r.getRawInt() == matcher.prefix() && matcher.match(r);
						} catch (RuntimeException e) {
							fail("length " + length + " cut " + cut + " on " + kind.name() + ": " + e);
							return;
						}
						if (!wholeNamePresent) {
							assertFalse(matched, "length " + length + " cut " + cut + " on " + kind.name());
						}
					}
				}
			}
		}
	}

	@Test
	void javaCallPassesTheArgumentsTheMatcherUses() {
		for (int length = 2; length <= 43; length++) {
			FieldNameMatcher matcher = FieldNameMatcher.of(nameOfLength(length));
			String call = matcher.javaCall("reader");
			assertTrue(call.startsWith("reader.nextIfName4Match" + length + "("), call);
			int tail = length - 1;
			int expectedArgs = tail / 8 + ((tail % 8) >= 4 ? 1 : 0) + ((tail % 8) % 4 == 3 ? 1 : 0);
			String inside = call.substring(call.indexOf('(') + 1, call.length() - 1);
			int actualArgs = inside.isEmpty() ? 0 : inside.split(", ").length;
			assertEquals(expectedArgs, actualArgs, call);
		}
	}

	private static void assertRejectedWithoutConsuming(FieldNameMatcher matcher, String json, String what) {
		List<ReaderKind> kinds = new ArrayList<>(KINDS);
		for (ReaderKind kind : kinds) {
			try (JSONReader r = kind.open().apply(json)) {
				r.nextIfObjectStart();
				int offset = r.getOffset();
				char current = r.current();
				// exactly how callers use it: prefix compare first, then the rest of the name
				boolean accepted = r.getRawInt() == matcher.prefix() && matcher.match(r);
				assertFalse(accepted, what + " on " + kind.name() + ": " + json);
				assertEquals(offset, r.getOffset(), what + " on " + kind.name() + ": consumed input");
				assertEquals(current, r.current(), what + " on " + kind.name() + ": moved");
			}
		}
	}
}
