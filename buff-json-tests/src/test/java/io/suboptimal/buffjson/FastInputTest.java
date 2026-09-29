package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import com.alibaba.fastjson2.JSONReader;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;

import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.internal.FastInput;

/**
 * {@link FastInput} accepts canonical proto3 JSON and throws
 * {@link FastInput.Bail} for everything else, and its callers re-run bailed
 * input through the general decoder. Two things therefore have to hold for
 * every scalar reader:
 *
 * <ol>
 * <li><b>accepted input is decoded exactly</b> -- compared with the JDK's own
 * parsers (bit-for-bit for floating point);
 * <li><b>anything not provably canonical bails</b> -- so no lenient
 * interpretation (leading zeros, truncation of fractions, lone surrogates,
 * overlong UTF-8, ...) can ever leak into a result.
 * </ol>
 */
class FastInputTest {

	private static FastInput input(String json) {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		return new FastInput(bytes, 0, bytes.length);
	}

	private static FastInput input(byte[] bytes) {
		return new FastInput(bytes, 0, bytes.length);
	}

	/**
	 * Reads the single value in {@code json} and checks the whole document was
	 * consumed.
	 */
	private static <T> T readAll(String json, Function<FastInput, T> reader) {
		FastInput in = input(json);
		T value = reader.apply(in);
		assertTrue(in.atEnd(), "unconsumed input after " + json);
		return value;
	}

	private static void assertBails(String json, Function<FastInput, ?> reader) {
		assertBails(json.getBytes(StandardCharsets.UTF_8), json, reader);
	}

	private static void assertBails(byte[] json, String description, Function<FastInput, ?> reader) {
		FastInput in;
		try {
			in = input(json);
			Object value = reader.apply(in);
			// A reader may legitimately stop early; the document must then not be
			// "finished".
			assertFalse(in.atEnd(), "accepted " + description + " as " + value);
		} catch (FastInput.Bail expected) {
			// ok
		}
	}

	// ------------------------------------------------------------------ integers

	@Test
	void int32Canonical() {
		int[] values = {0, 1, -1, 9, 10, 99, 100, 12345, -12345, Integer.MAX_VALUE, Integer.MIN_VALUE,
				Integer.MAX_VALUE - 1, Integer.MIN_VALUE + 1, 1_000_000_000, -1_000_000_000};
		for (int v : values) {
			assertEquals(v, readAll(Integer.toString(v), FastInput::int32));
			assertEquals(v, readAll(" \n\t" + v + "\r ", FastInput::int32));
		}
		Random rng = new Random(1);
		for (int i = 0; i < 20_000; i++) {
			int v = rng.nextInt() >> rng.nextInt(32);
			assertEquals(v, readAll(Integer.toString(v), FastInput::int32));
		}
		assertEquals(0, readAll("-0", FastInput::int32));
	}

	@Test
	void int32NonCanonicalBails() {
		for (String s : new String[]{"01", "-01", "+1", "1.0", "1.5", "1e2", "1E2", "2147483648", "-2147483649",
				"99999999999", "", "-", "--1", "1-", "0x10", "1_0", "١٢٣", "\"1\"", "true", "null", "[1]", "{}", "1a",
				"1,", "1 1", "٣"}) {
			assertBails(s, FastInput::int32);
		}
	}

	@Test
	void uint32Canonical() {
		long[] values = {0, 1, 2147483647L, 2147483648L, 4294967295L, 3_000_000_000L};
		for (long v : values) {
			assertEquals((int) v, readAll(Long.toString(v), FastInput::uint32));
		}
		for (String s : new String[]{"4294967296", "-1", "01", "1.0", "+1", "\"1\""}) {
			assertBails(s, FastInput::uint32);
		}
	}

	@Test
	void int64AcceptsBareAndQuotedCanonicalForms() {
		long[] values = {0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1,
				999_999_999_999_999_999L, -999_999_999_999_999_999L, 1_000_000_000_000_000_000L,
				-1_000_000_000_000_000_000L, 4_611_686_018_427_387_904L, 9_007_199_254_740_993L};
		for (long v : values) {
			assertEquals(v, readAll(Long.toString(v), FastInput::int64));
			assertEquals(v, readAll("\"" + v + "\"", FastInput::int64));
		}
		Random rng = new Random(2);
		for (int i = 0; i < 20_000; i++) {
			long v = rng.nextLong() >> rng.nextInt(64);
			assertEquals(v, readAll("\"" + v + "\"", FastInput::int64));
			assertEquals(v, readAll(Long.toString(v), FastInput::int64));
		}
	}

	@Test
	void int64NonCanonicalOrOutOfRangeBails() {
		for (String s : new String[]{"9223372036854775808", "-9223372036854775809", "\"9223372036854775808\"",
				"92233720368547758070", "01", "\"01\"", "\"+1\"", "+1", "1.0", "\"1.0\"", "1e3", "\"1e3\"", "\"\"",
				"\"", "\"12", "12\"", "\" 12\"", "\"12 \"", "\"1 2\"", "\"-\"", "\"--1\"", "\"0x1\"", "-", "true",
				"\"true\"", "null", "\"null\"", "1L", "\"1L\"", "1.5", "\"1.5\"", "\"\\u0031\""}) {
			assertBails(s, FastInput::int64);
		}
	}

	@Test
	void uint64Canonical() {
		BigInteger max = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
		List<BigInteger> values = new ArrayList<>(
				List.of(BigInteger.ZERO, BigInteger.ONE, max, max.subtract(BigInteger.ONE),
						BigInteger.ONE.shiftLeft(63), BigInteger.ONE.shiftLeft(63).subtract(BigInteger.ONE),
						new BigInteger("10000000000000000000"), new BigInteger("18446744073709551610")));
		Random rng = new Random(3);
		for (int i = 0; i < 5_000; i++) {
			values.add(new BigInteger(1 + rng.nextInt(64), rng));
		}
		for (BigInteger v : values) {
			assertEquals(v.longValue(), readAll(v.toString(), FastInput::uint64));
			assertEquals(v.longValue(), readAll("\"" + v + "\"", FastInput::uint64));
		}
		for (String s : new String[]{"18446744073709551616", "\"18446744073709551616\"", "184467440737095516150",
				"99999999999999999999", "-1", "\"-1\"", "01", "1.0", "+1", "\"\"", "1e2"}) {
			assertBails(s, FastInput::uint64);
		}
	}

	@Test
	void quotedMapKeys() {
		assertEquals(-5, readAll("\"-5\"", FastInput::int32Key));
		assertEquals(Integer.MAX_VALUE, readAll("\"2147483647\"", FastInput::int32Key));
		assertEquals(-1, readAll("\"4294967295\"", FastInput::uint32Key));
		assertEquals(Long.MIN_VALUE, readAll("\"-9223372036854775808\"", FastInput::int64Key));
		assertEquals(-1L, readAll("\"18446744073709551615\"", FastInput::uint64Key));
		assertEquals(true, readAll("\"true\"", FastInput::boolKey));
		assertEquals(false, readAll("\"false\"", FastInput::boolKey));
		for (String s : new String[]{"5", "\"05\"", "\"+5\"", "\"5.0\"", "\"\"", "\"2147483648\""}) {
			assertBails(s, FastInput::int32Key);
		}
		for (String s : new String[]{"\"TRUE\"", "\"True\"", "true", "\"yes\"", "\"1\"", "\"\""}) {
			assertBails(s, FastInput::boolKey);
		}
		assertBails("\"-1\"", FastInput::uint32Key);
		// a JSON object key is always a string, also for the unsigned 64-bit reader
		assertBails("5", FastInput::uint64Key);
		assertBails("18446744073709551615", FastInput::uint64Key);
	}

	/**
	 * A key as a generated map reader uses it: the key, then the colon, then a
	 * value.
	 */
	private static <T> T readKey(String keyToken, String separator, Function<FastInput, T> reader) {
		FastInput in = input("{" + keyToken + separator + "1}");
		in.objectStart();
		T key = reader.apply(in);
		in.colon();
		assertEquals(1, in.int32());
		in.objectEndRequired();
		assertTrue(in.atEnd());
		return key;
	}

	@Test
	void quotedMapKeysAreFollowedByAColon() {
		for (String separator : new String[]{":", " :", "\n:", ": "}) {
			assertEquals(-5, readKey("\"-5\"", separator, FastInput::int32Key));
			assertEquals(-1, readKey("\"4294967295\"", separator, FastInput::uint32Key));
			assertEquals(Long.MIN_VALUE, readKey("\"-9223372036854775808\"", separator, FastInput::int64Key));
			assertEquals(-1L, readKey("\"18446744073709551615\"", separator, FastInput::uint64Key));
			assertEquals(true, readKey("\"true\"", separator, FastInput::boolKey));
			assertEquals("k", readKey("\"k\"", separator, FastInput::string));
		}
	}

	// --------------------------------------------------------------- floating
	// point

	private static void assertDouble(String token) {
		double expected = Double.parseDouble(token);
		FastInput in = input(token);
		double actual;
		try {
			actual = in.float64();
		} catch (FastInput.Bail bail) {
			assertTrue(Double.isInfinite(expected), "unexpected bail for " + token);
			return;
		}
		assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
				token + ": expected " + expected + " but was " + actual);
		assertTrue(in.atEnd());
	}

	private static void assertFloat(String token) {
		float expected = Float.parseFloat(token);
		FastInput in = input(token);
		float actual;
		try {
			actual = in.float32();
		} catch (FastInput.Bail bail) {
			assertTrue(Float.isInfinite(expected), "unexpected bail for " + token);
			return;
		}
		assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual),
				token + ": expected " + expected + " but was " + actual);
	}

	@Test
	void doublesMatchTheJdkParserBitForBit() {
		String[] hard = {"0", "-0", "0.0", "-0.0", "1", "-1", "0.1", "0.2", "0.3", "1.5", "123456789012345678",
				"9007199254740993", "9007199254740992", "9007199254740991", "1e22", "1e23", "8.41e21", "1e-22", "1e-23",
				"4.9e-324", "2.4703282292062327e-324", "2.4703282292062328e-324", "2.2250738585072011e-308",
				"2.2250738585072014e-308", "1.7976931348623157e308", "1.7976931348623158e308", "5e-324", "1e-400",
				"0.000001", "0.0000001", "123.456e5", "123.456E-5", "1E5", "1e+5", "1e-5", "100000000000000000000",
				"0.1000000000000000055511151231257827", "3.14159265358979323846264338327950288",
				"1.00000000000000011102230246251565404236316680908203125",
				"1.00000000000000011102230246251565404236316680908203126", "37.7749295", "-122.4194155",
				"0.30000000000000004", "2.718281828459045", "1234567890123456789012345678901234567890",
				"0.00000000000000000000000000000000000001", "8.5e-5", "5.0e-1"};
		for (String token : hard) {
			assertDouble(token);
			if (!token.startsWith("-")) {
				assertDouble("-" + token);
			}
		}
		Random rng = new Random(4);
		for (int i = 0; i < 300_000; i++) {
			double d = Double.longBitsToDouble(rng.nextLong());
			if (Double.isNaN(d) || Double.isInfinite(d)) {
				continue;
			}
			assertDouble(Double.toString(d));
		}
		for (int i = 0; i < 200_000; i++) {
			// human-scale values with few digits (the common case: Clinger's exact fast
			// path)
			double d = rng.nextInt(2_000_000) / Math.pow(10, rng.nextInt(9));
			assertDouble(Double.toString(d));
			assertDouble(BigDecimal.valueOf(d).toPlainString());
		}
		for (int i = 0; i < 200_000; i++) {
			// arbitrary digit strings with a decimal point and exponent
			StringBuilder sb = new StringBuilder();
			int intDigits = 1 + rng.nextInt(20);
			sb.append(rng.nextBoolean() ? "-" : "");
			sb.append((char) ('1' + rng.nextInt(9)));
			for (int k = 1; k < intDigits; k++) {
				sb.append((char) ('0' + rng.nextInt(10)));
			}
			if (rng.nextBoolean()) {
				sb.append('.');
				int frac = 1 + rng.nextInt(25);
				for (int k = 0; k < frac; k++) {
					sb.append((char) ('0' + rng.nextInt(10)));
				}
			}
			if (rng.nextInt(3) == 0) {
				sb.append(rng.nextBoolean() ? 'e' : 'E')
						.append(rng.nextInt(3) == 0 ? "+" : rng.nextBoolean() ? "-" : "").append(rng.nextInt(40));
			}
			assertDouble(sb.toString());
		}
	}

	@Test
	void floatsMatchTheJdkParserBitForBit() {
		Random rng = new Random(5);
		for (String token : new String[]{"0", "-0", "0.1", "1.1", "16777216", "16777217", "3.4028235e38",
				"3.4028236e38", "1.4e-45", "7e-46", "0.3", "33554431", "1.17549435e-38", "8388608.5", "1e10", "1e11",
				"123456789"}) {
			assertFloat(token);
		}
		for (int i = 0; i < 300_000; i++) {
			float f = Float.intBitsToFloat(rng.nextInt());
			if (Float.isNaN(f) || Float.isInfinite(f)) {
				continue;
			}
			assertFloat(Float.toString(f));
			assertFloat(Double.toString(f * 1.0000001d)); // needs correct float rounding of a longer decimal
		}
		for (int i = 0; i < 100_000; i++) {
			assertFloat(Double.toString(rng.nextInt(200_000) / Math.pow(10, rng.nextInt(8))));
		}
	}

	@Test
	void specialFloatingPointValuesAreQuotedOnly() {
		assertTrue(Double.isNaN(readAll("\"NaN\"", FastInput::float64)));
		assertEquals(Double.POSITIVE_INFINITY, readAll("\"Infinity\"", FastInput::float64));
		assertEquals(Double.NEGATIVE_INFINITY, readAll("\"-Infinity\"", FastInput::float64));
		assertTrue(Float.isNaN(readAll("\"NaN\"", FastInput::float32)));
		assertEquals(Float.NEGATIVE_INFINITY, readAll("\"-Infinity\"", FastInput::float32));
		for (String s : new String[]{"NaN", "Infinity", "-Infinity", "\"nan\"", "\"inf\"", "\"+Infinity\"", "\"1.5\"",
				"\"\"", "\"NaN", "\"Infinit\"", "\"infinity\""}) {
			assertBails(s, FastInput::float64);
			assertBails(s, FastInput::float32);
		}
	}

	@Test
	void nonCanonicalNumbersBail() {
		for (String s : new String[]{"00", "01.5", "-01", ".5", "-.5", "5.", "-", "+5", "1e", "1e+", "1e-", "1.e5",
				"0x1p3", "1_000", "1,5", "1.5.5", "1e5e5", "1e400", "-1e400", "3.5e38f", "1d", "1.5f", "1L", "true",
				"null", "[]", "e5", "٣.٥", "1e99999999999", "1.7976931348623159e308"}) {
			assertBails(s, FastInput::float64);
		}
		assertBails("3.5e38", FastInput::float32); // overflows float
		assertBails("-3.5e38", FastInput::float32);
	}

	// -----------------------------------------------------------------------
	// strings

	private static String jsonString(String s, boolean escapeNonAscii, Random rng) {
		StringBuilder sb = new StringBuilder("\"");
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				case '/' -> sb.append(rng.nextInt(4) == 0 ? "\\/" : "/");
				default -> {
					if (c < 0x20 || (escapeNonAscii && c > 0x7e)) {
						sb.append(String.format(rng.nextBoolean() ? "\\u%04x" : "\\u%04X", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"').toString();
	}

	private static String randomString(Random rng, int maxLength) {
		StringBuilder sb = new StringBuilder();
		int length = rng.nextInt(maxLength + 1);
		for (int i = 0; i < length; i++) {
			switch (rng.nextInt(9)) {
				case 0 -> sb.append((char) ('a' + rng.nextInt(26)));
				case 1 -> sb.append((char) (0x20 + rng.nextInt(0x5f)));
				case 2 -> sb.append((char) rng.nextInt(0x20)); // control characters
				case 3 -> sb.append("\"\\/".charAt(rng.nextInt(3)));
				case 4 -> sb.append((char) (0x80 + rng.nextInt(0x780))); // 2-byte UTF-8
				case 5 -> { // 3-byte UTF-8, excluding surrogates
					char c;
					do {
						c = (char) (0x800 + rng.nextInt(0xF800));
					} while (Character.isSurrogate(c));
					sb.append(c);
				}
				case 6 -> sb.appendCodePoint(0x10000 + rng.nextInt(0x100000)); // 4-byte UTF-8
				case 7 -> sb.append((char) ('0' + rng.nextInt(10)));
				default -> sb.append(' ');
			}
		}
		return sb.toString();
	}

	@Test
	void stringsRoundTripThroughEveryEncoding() {
		Random rng = new Random(6);
		for (int i = 0; i < 100_000; i++) {
			String s = randomString(rng, i % 5 == 0 ? 200 : 24);
			for (boolean escapeNonAscii : new boolean[]{false, true}) {
				String json = jsonString(s, escapeNonAscii, rng);
				assertEquals(s, readAll(json, FastInput::string), json);
				// and identical to what fastjson2's own reader produces
				try (JSONReader reader = JSONReader.of(json.getBytes(StandardCharsets.UTF_8))) {
					assertEquals(reader.readString(), readAll(json, FastInput::string), json);
				}
			}
		}
		assertEquals("", readAll("\"\"", FastInput::string));
	}

	@Test
	void stringsAcceptLoneSurrogateEscapesLikeTheGeneralReader() {
		// \\ud800 alone is not a valid UTF-8 string, but JSON allows the escape and the
		// general reader keeps the lone surrogate; the fast reader must agree, not
		// "fix" it.
		String json = "\"a\\ud800b\\udc00\"";
		try (JSONReader reader = JSONReader.of(json.getBytes(StandardCharsets.UTF_8))) {
			assertEquals(reader.readString(), readAll(json, FastInput::string));
		}
		assertEquals("a\ud800b\udc00", readAll(json, FastInput::string));
	}

	@Test
	void malformedStringsBail() {
		List<byte[]> bad = new ArrayList<>();
		for (String s : new String[]{"\"abc", "\"abc\\", "\"a\\qb\"", "\"a\\u12\"", "\"a\\uZZZZ\"", "\"a\\u+123\"",
				"\"a\nb\"", "\"a\tb\"", "\"a\u0001b\"", "\"\\x41\"", "\"\\'\"", "abc", "'abc'", "\"\\", "\"\\u",
				"\"\\u1"}) {
			bad.add(s.getBytes(StandardCharsets.UTF_8));
		}
		int[][] malformed = {{0xC0, 0x80}, {0xC1, 0xBF}, {0x80}, {0xBF}, {0xC2}, {0xC2, 0x20}, {0xE0, 0x80, 0x80},
				{0xE0, 0x9F, 0xBF}, {0xED, 0xA0, 0x80}, {0xED, 0xBF, 0xBF}, {0xE2, 0x82}, {0xF0, 0x80, 0x80, 0x80},
				{0xF0, 0x8F, 0xBF, 0xBF}, {0xF4, 0x90, 0x80, 0x80}, {0xF5, 0x80, 0x80, 0x80},
				{0xF8, 0x88, 0x80, 0x80, 0x80}, {0xFF}, {0xFE}, {0xF0, 0x9F, 0x98}, {0xF0, 0x9F}, {0xC3, 0xA9, 0xA9}};
		for (int[] seq : malformed) {
			byte[] doc = new byte[seq.length + 4];
			doc[0] = '"';
			doc[1] = 'a';
			for (int i = 0; i < seq.length; i++) {
				doc[2 + i] = (byte) seq[i];
			}
			doc[doc.length - 2] = 'b';
			doc[doc.length - 1] = '"';
			bad.add(doc);
		}
		for (byte[] doc : bad) {
			assertBails(doc, new String(doc, StandardCharsets.ISO_8859_1), FastInput::string);
		}
	}

	// ------------------------------------------------------------------------
	// bytes

	@Test
	void base64BytesMatchTheGeneralDecoder() {
		Random rng = new Random(7);
		for (int i = 0; i < 20_000; i++) {
			byte[] raw = new byte[rng.nextInt(64)];
			rng.nextBytes(raw);
			for (Base64.Encoder encoder : new Base64.Encoder[]{Base64.getEncoder(),
					Base64.getEncoder().withoutPadding()}) {
				String json = "\"" + encoder.encodeToString(raw) + "\"";
				assertEquals(ByteString.copyFrom(raw), readAll(json, FastInput::bytes), json);
			}
		}
		assertEquals(ByteString.EMPTY, readAll("\"\"", FastInput::bytes));
		for (String s : new String[]{"\"AAA*\"", "\"AA=A\"", "\"A\"", "\"AAAAA\"", "\"AA AA\"", "\"AA\\nAA\"",
				"\"-_-_\"", "\"AA==AA\"", "AAAA", "\"AAAA", "\"AAAA\\/AAA\"", "\"\u00e9AAA\""}) {
			assertBails(s, FastInput::bytes);
		}
	}

	// ------------------------------------------------------ Timestamp and Duration

	private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
			.withZone(ZoneOffset.UTC);

	@Test
	void canonicalTimestampsMatchInstantParse() {
		Random rng = new Random(8);
		for (int i = 0; i < 200_000; i++) {
			long seconds = -62_135_596_800L + (long) (rng.nextDouble() * (253_402_300_799L + 62_135_596_800L));
			int nanos = rng.nextInt(1_000_000_000);
			int precision = new int[]{0, 3, 6, 9}[rng.nextInt(4)];
			nanos = switch (precision) {
				case 0 -> 0;
				case 3 -> nanos / 1_000_000 * 1_000_000;
				case 6 -> nanos / 1_000 * 1_000;
				default -> nanos;
			};
			String text = SECONDS.format(Instant.ofEpochSecond(seconds));
			if (precision == 3) {
				text += String.format(".%03d", nanos / 1_000_000);
			} else if (precision == 6) {
				text += String.format(".%06d", nanos / 1_000);
			} else if (precision == 9) {
				text += String.format(".%09d", nanos);
			}
			text += "Z";
			Instant expected = Instant.parse(text);
			Timestamp actual = readAll("\"" + text + "\"", FastInput::timestamp);
			assertEquals(expected.getEpochSecond(), actual.getSeconds(), text);
			assertEquals(expected.getNano(), actual.getNanos(), text);
		}
		for (String edge : new String[]{"0001-01-01T00:00:00Z", "9999-12-31T23:59:59.999999999Z",
				"1970-01-01T00:00:00Z", "2000-02-29T12:00:00Z", "1900-02-28T23:59:59Z", "2100-03-01T00:00:00.000Z",
				"2024-12-31T23:59:59.999Z"}) {
			Instant expected = Instant.parse(edge);
			Timestamp actual = readAll("\"" + edge + "\"", FastInput::timestamp);
			assertEquals(expected.getEpochSecond(), actual.getSeconds(), edge);
			assertEquals(expected.getNano(), actual.getNanos(), edge);
		}
	}

	@Test
	void nonCanonicalTimestampsBail() {
		for (String s : new String[]{"2024-01-01T00:00:00+01:00", "2024-01-01T00:00:00-05:00", "2024-01-01t00:00:00Z",
				"2024-01-01T00:00:00z", "2024-01-01 00:00:00Z", "2024-01-01T00:00:00.1Z", "2024-01-01T00:00:00.12Z",
				"2024-01-01T00:00:00.1234Z", "2024-01-01T00:00:00.12345Z", "2024-01-01T00:00:00.1234567Z",
				"2024-01-01T00:00:00.12345678Z", "2024-01-01T00:00:00.1234567890Z", "2024-01-01T00:00:60Z",
				"2024-01-01T24:00:00Z", "2024-01-01T00:60:00Z", "2024-13-01T00:00:00Z", "2024-00-01T00:00:00Z",
				"2024-01-32T00:00:00Z", "2023-02-29T00:00:00Z", "2024-04-31T00:00:00Z", "0000-01-01T00:00:00Z",
				"2024-01-01T00:00:00", "2024-01-01", "24-01-01T00:00:00Z", "2024-1-1T0:0:0Z", "+2024-01-01T00:00:00Z",
				"2024-01-01T00:00:00.Z", "2024-01-01T00:00:0aZ", "2024-01-01T00:00:00ZZ", "", "2024-01-01T00:00:00Z ",
				"2024-01-01T00:00:00,123Z"}) {
			assertBails("\"" + s + "\"", FastInput::timestamp);
		}
		assertBails("2024-01-01T00:00:00Z", FastInput::timestamp);
		assertBails("\"2024-01-01T00:00:00Z", FastInput::timestamp);
		assertBails("null", FastInput::timestamp);
	}

	@Test
	void canonicalDurations() {
		Random rng = new Random(9);
		for (int i = 0; i < 100_000; i++) {
			long seconds = (long) (rng.nextDouble() * 315_576_000_001L);
			int digits = rng.nextInt(10);
			int nanos = digits == 0 ? 0 : rng.nextInt((int) Math.pow(10, digits));
			boolean negative = rng.nextBoolean();
			String fraction = digits == 0 ? "" : "." + String.format("%0" + digits + "d", nanos);
			String text = (negative ? "-" : "") + seconds + fraction + "s";
			BigDecimal value = new BigDecimal(seconds + fraction);
			if (negative) {
				value = value.negate();
			}
			long expectedSeconds = value.longValue();
			int expectedNanos = value.subtract(BigDecimal.valueOf(expectedSeconds)).movePointRight(9).intValue();
			Duration actual = readAll("\"" + text + "\"", FastInput::duration);
			assertEquals(expectedSeconds, actual.getSeconds(), text);
			assertEquals(expectedNanos, actual.getNanos(), text);
		}
		assertEquals(Duration.newBuilder().setSeconds(315_576_000_000L).build(),
				readAll("\"315576000000s\"", FastInput::duration));
		assertEquals(Duration.newBuilder().setSeconds(-315_576_000_000L).setNanos(-999_999_999).build(),
				readAll("\"-315576000000.999999999s\"", FastInput::duration));
	}

	@Test
	void nonCanonicalDurationsBail() {
		for (String s : new String[]{"1", "1S", "s", "-s", "+1s", "1.s", ".5s", "1.5", "01s", "1.1234567891s", "1e3s",
				"315576000001s", "-315576000001s", "1 s", " 1s", "1s ", "1ss", "--1s", "1.-5s", "9999999999999s"}) {
			assertBails("\"" + s + "\"", FastInput::duration);
		}
	}

	// ------------------------------------------------------------ structure and
	// skipping

	// ------------------------------------------------ strings, differentially

	private static String randomText(Random rng, int maxChars, int maxCodePoint) {
		StringBuilder sb = new StringBuilder();
		int chars = rng.nextInt(maxChars + 1);
		while (sb.length() < chars) {
			int cp = switch (rng.nextInt(6)) {
				case 0 -> 0x20 + rng.nextInt(0x5f); // printable ASCII
				case 1 -> rng.nextInt(0x100); // Latin-1, control characters included
				case 2 -> rng.nextInt(0x800);
				case 3 -> 0x800 + rng.nextInt(0xF800);
				case 4 -> 0x10000 + rng.nextInt(0x100000);
				default -> 'a' + rng.nextInt(26);
			};
			cp = Math.min(cp, maxCodePoint);
			if (cp >= 0xD800 && cp <= 0xDFFF) {
				continue; // surrogates only ever appear as pairs of a supplementary code point, or
							// escaped
			}
			sb.appendCodePoint(cp);
		}
		return sb.toString();
	}

	/**
	 * A JSON string literal of {@code text}: what must be escaped (controls, quote,
	 * backslash, lone surrogates) is, and with {@code escapeMore} so are random
	 * other code points.
	 */
	private static String literal(String text, Random rng, boolean escapeMore) {
		StringBuilder sb = new StringBuilder("\"");
		for (int i = 0; i < text.length();) {
			int cp = text.codePointAt(i);
			int length = Character.charCount(cp);
			boolean lone = cp >= 0xD800 && cp <= 0xDFFF;
			boolean escape = lone || cp < 0x20 || cp == '"' || cp == '\\' || (escapeMore && rng.nextInt(6) == 0);
			if (!escape) {
				sb.appendCodePoint(cp);
			} else if (cp == '"' && rng.nextBoolean()) {
				sb.append("\\\"");
			} else if (cp == '\\' && rng.nextBoolean()) {
				sb.append("\\\\");
			} else if (cp == '\n' && rng.nextBoolean()) {
				sb.append("\\n");
			} else if (cp == '/' && rng.nextBoolean()) {
				sb.append("\\/");
			} else {
				for (int k = 0; k < length; k++) {
					sb.append(String.format("\\u%04x", (int) text.charAt(i + k)));
				}
			}
			i += length;
		}
		return sb.append('"').toString();
	}

	@Test
	void stringsDecodeToExactlyTheirCharactersInEveryEncoding() {
		Random rng = new Random(31);
		for (int round = 0; round < 60_000; round++) {
			String text = randomText(rng, 40, 0x10FFFF);
			if (rng.nextInt(20) == 0) {
				// a lone surrogate, which only an escape can carry
				text = text + (rng.nextBoolean() ? "\ud800" : "\udc00") + randomText(rng, 3, 0x7f);
			}
			String json = literal(text, rng, rng.nextBoolean());
			// (a) UTF-8 bytes
			byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
			FastInput in = new FastInput(utf8, 0, utf8.length);
			assertEquals(text, in.string(), json);
			assertTrue(in.atEnd());
			// (b) a Latin-1 String's bytes, when the text is Latin-1: raw bytes are
			// characters
			if (text.chars().allMatch(c -> c < 0x100)) {
				byte[] latin1 = json.getBytes(StandardCharsets.ISO_8859_1);
				FastInput l = new FastInput(latin1, 0, latin1.length, true);
				assertEquals(text, l.string(), json);
				assertTrue(l.atEnd());
			}
		}
	}

	@Test
	void malformedUtf8BailsExactlyWhereTheStrictDecoderRejects() throws Exception {
		Random rng = new Random(37);
		for (int round = 0; round < 120_000; round++) {
			String text = randomText(rng, 12, 0x10FFFF);
			byte[] content = text.getBytes(StandardCharsets.UTF_8);
			// damage a few bytes, or splice in structural ones
			for (int k = rng.nextInt(3); k > 0 && content.length > 0; k--) {
				content[rng.nextInt(
						content.length)] = (byte) (rng.nextInt(4) == 0 ? 0x80 + rng.nextInt(0x80) : rng.nextInt(256));
			}
			byte[] json = new byte[content.length + 2];
			json[0] = '"';
			System.arraycopy(content, 0, json, 1, content.length);
			json[json.length - 1] = '"';
			// the reference: a JSON string body must not hold a raw control character,
			// quote
			// or backslash (those would end or escape the string), and its UTF-8 must be
			// strict
			boolean structural = false;
			for (byte c : content) {
				structural |= c == '"' || c == '\\' || (c >= 0 && c < 0x20);
			}
			String expected = null;
			if (!structural) {
				try {
					expected = StandardCharsets.UTF_8.newDecoder()
							.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
							.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
							.decode(java.nio.ByteBuffer.wrap(content)).toString();
				} catch (java.nio.charset.CharacterCodingException malformed) {
					expected = null;
				}
			}
			FastInput in = new FastInput(json, 0, json.length);
			if (expected == null) {
				if (structural) {
					continue; // a quote or backslash changes what the literal is; not this test's subject
				}
				try {
					String got = in.string();
					fail("accepted malformed UTF-8 " + java.util.HexFormat.of().formatHex(json) + " as " + got);
				} catch (FastInput.Bail expectedBail) {
					// ok
				}
			} else {
				assertEquals(expected, in.string(), java.util.HexFormat.of().formatHex(json));
			}
		}
	}

	// ------------------------------------------------ integers, differentially

	private static final java.util.regex.Pattern CANONICAL_INTEGER = java.util.regex.Pattern
			.compile("-?(0|[1-9][0-9]*)");

	/**
	 * What a strict reader must return for {@code token} (a bare integer followed
	 * by {@code after}), or empty if it must bail.
	 */
	private static java.util.Optional<BigInteger> expectedInteger(String token, String after, BigInteger min,
			BigInteger max) {
		if (!CANONICAL_INTEGER.matcher(token).matches() || !(after.isEmpty() || after.startsWith(",")
				|| after.startsWith("}") || after.startsWith("]") || after.startsWith(" "))) {
			return java.util.Optional.empty();
		}
		if (min.signum() >= 0 && token.startsWith("-")) {
			return java.util.Optional.empty(); // unsigned readers take no sign, not even for -0
		}
		BigInteger v = new BigInteger(token);
		return v.compareTo(min) >= 0 && v.compareTo(max) <= 0 ? java.util.Optional.of(v) : java.util.Optional.empty();
	}

	private static String randomIntegerToken(Random rng) {
		int length = 1 + rng.nextInt(22);
		StringBuilder sb = new StringBuilder();
		if (rng.nextInt(4) == 0) {
			sb.append('-');
		}
		// mostly a non-zero first digit; sometimes a zero (a lone 0, or a leading zero)
		sb.append(rng.nextInt(8) == 0 ? '0' : (char) ('1' + rng.nextInt(9)));
		for (int i = 1; i < length; i++) {
			sb.append((char) ('0' + rng.nextInt(10)));
		}
		if (rng.nextInt(50) == 0) {
			sb.setCharAt(rng.nextInt(sb.length()), "x.+e-E_".charAt(rng.nextInt(7)));
		}
		return sb.toString();
	}

	/**
	 * Boundary values of every width, so that off-by-one range and length errors
	 * show.
	 */
	private static List<String> boundaryIntegers() {
		List<String> tokens = new ArrayList<>();
		for (BigInteger edge : new BigInteger[]{BigInteger.ZERO, BigInteger.ONE, BigInteger.valueOf(9),
				BigInteger.valueOf(10), BigInteger.valueOf(99999999), BigInteger.valueOf(100000000),
				BigInteger.valueOf(Integer.MAX_VALUE), BigInteger.valueOf(Integer.MIN_VALUE),
				BigInteger.valueOf(0xFFFFFFFFL), BigInteger.valueOf(0x100000000L), BigInteger.valueOf(Long.MAX_VALUE),
				BigInteger.valueOf(Long.MIN_VALUE), BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
				BigInteger.ONE.shiftLeft(64), BigInteger.TEN.pow(18), BigInteger.TEN.pow(19).subtract(BigInteger.ONE),
				BigInteger.TEN.pow(19), BigInteger.TEN.pow(20)}) {
			for (int delta = -2; delta <= 2; delta++) {
				tokens.add(edge.add(BigInteger.valueOf(delta)).toString());
			}
		}
		return tokens;
	}

	@Test
	void integerReadersMatchExactArithmeticForEveryLengthAndAlignment() {
		Random rng = new Random(21);
		BigInteger i32min = BigInteger.valueOf(Integer.MIN_VALUE), i32max = BigInteger.valueOf(Integer.MAX_VALUE);
		BigInteger u32max = BigInteger.valueOf(0xFFFFFFFFL);
		BigInteger i64min = BigInteger.valueOf(Long.MIN_VALUE), i64max = BigInteger.valueOf(Long.MAX_VALUE);
		BigInteger u64max = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
		String[] afters = {"", ",", "}", "]", " ", ".5", "e3", "x", ":"};
		List<String> tokens = boundaryIntegers();
		for (int i = 0; i < 300_000; i++) {
			tokens.add(randomIntegerToken(rng));
		}
		int checked = 0;
		for (String token : tokens) {
			String after = afters[rng.nextInt(afters.length)];
			String padding = " ".repeat(rng.nextInt(9)); // moves the number across word boundaries
			String json = padding + token + after;
			assertInteger(json, expectedInteger(token, after, i32min, i32max), FastInput::int32,
					BigInteger::intValueExact);
			assertInteger(json, expectedInteger(token, after, BigInteger.ZERO, u32max), FastInput::uint32,
					v -> v.intValue());
			assertInteger(json, expectedInteger(token, after, i64min, i64max), FastInput::int64,
					BigInteger::longValueExact);
			assertInteger(json, expectedInteger(token, after, BigInteger.ZERO, u64max), FastInput::uint64,
					v -> v.longValue());
			// int64 / uint64 also take a quoted integer; what follows the closing quote is
			// for the caller's next structural call to check
			String quoted = padding + "\"" + token + "\"" + after;
			assertInteger(quoted, expectedInteger(token, "", i64min, i64max), FastInput::int64,
					BigInteger::longValueExact);
			assertInteger(quoted, expectedInteger(token, "", BigInteger.ZERO, u64max), FastInput::uint64,
					v -> v.longValue());
			checked++;
		}
		assertTrue(checked > 300_000);
	}

	private static <T> void assertInteger(String json, java.util.Optional<BigInteger> expected,
			Function<FastInput, T> reader, Function<BigInteger, T> convert) {
		FastInput in;
		try {
			in = input(json);
		} catch (FastInput.Bail e) {
			assertTrue(expected.isEmpty(), json);
			return;
		}
		try {
			T actual = reader.apply(in);
			assertTrue(expected.isPresent(), "accepted " + json + " as " + actual);
			assertEquals(convert.apply(expected.get()), actual, json);
		} catch (FastInput.Bail bail) {
			if (expected.isPresent()) {
				fail("bailed on " + json + ", expected " + expected.get());
			}
		}
	}

	// ------------------------------------------------ straight-line helpers
	//
	// Generated readFast methods call these directly with a position of their own.

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static long packed(int value, int position) {
		return ((long) value << 32) | position;
	}

	@Test
	void packedInt32ReturnsValueAndNextPosition() {
		assertEquals(packed(0, 1), FastInput.int32At(bytes("0,"), 0));
		assertEquals(packed(-5, 2), FastInput.int32At(bytes("-5}"), 0));
		assertEquals(packed(Integer.MAX_VALUE, 10), FastInput.int32At(bytes("2147483647]"), 0));
		assertEquals(packed(Integer.MIN_VALUE, 11), FastInput.int32At(bytes("-2147483648,"), 0));
		// starts wherever the caller says, and a number may be the last byte
		assertEquals(packed(42, 5), FastInput.int32At(bytes("xxx42"), 3));
		assertEquals(packed(0, 1), FastInput.int32At(bytes("0"), 0));
		// it reads the digits only: what follows is the caller's business
		assertEquals(packed(1, 1), FastInput.int32At(bytes("1.5"), 0));
		assertEquals(packed(12, 2), FastInput.int32At(bytes("12x"), 0));
		for (String bad : new String[]{"", "-", "x", "01", "-01", "00", "2147483648", "-2147483649", "12345678901",
				"99999999999999999999", "+1"}) {
			assertThrows(FastInput.Bail.class, () -> FastInput.int32At(bytes(bad), 0), bad);
		}
	}

	@Test
	void packedUint32KeepsTheBitsInTheHighHalf() {
		assertEquals(packed(-1, 10), FastInput.uint32At(bytes("4294967295,"), 0));
		assertEquals(packed(0, 1), FastInput.uint32At(bytes("0}"), 0));
		assertEquals(packed(Integer.MIN_VALUE, 10), FastInput.uint32At(bytes("2147483648,"), 0));
		for (String bad : new String[]{"", "-1", "4294967296", "01", "x", "99999999999"}) {
			assertThrows(FastInput.Bail.class, () -> FastInput.uint32At(bytes(bad), 0), bad);
		}
	}

	@Test
	void packedKeysAreQuotedAndEndAfterTheClosingQuote() {
		assertEquals(packed(-7, 4), FastInput.int32KeyAt(bytes("\"-7\":"), 0));
		assertEquals(packed(-1, 12), FastInput.uint32KeyAt(bytes("\"4294967295\":"), 0));
		for (String bad : new String[]{"5", "\"5", "\"\"", "\"5x\"", "\"05\"", "\" 5\"", "\"5 \"", "\"2147483648\""}) {
			assertBailsOrOverruns(() -> FastInput.int32KeyAt(bytes(bad), 0), bad);
		}
		assertBailsOrOverruns(() -> FastInput.uint32KeyAt(bytes("\"-1\""), 0), "\"-1\"");
		assertBailsOrOverruns(() -> FastInput.uint32KeyAt(bytes("7"), 0), "7");
	}

	/**
	 * Running off the end of the array is how generated code meets a truncated
	 * document; callers treat it like a Bail.
	 */
	private static void assertBailsOrOverruns(Runnable read, String what) {
		try {
			read.run();
			fail("accepted " + what);
		} catch (FastInput.Bail | IndexOutOfBoundsException expected) {
			// ok
		}
	}

	@Test
	void whitespaceSkipOnlyPassesJsonWhitespace() {
		assertEquals(0, FastInput.ws(bytes("x"), 0));
		assertEquals(1, FastInput.ws(bytes(" x"), 0)); // the single blank
		assertEquals(1, FastInput.ws(bytes(" "), 0)); // ... also as the very last byte
		assertEquals(2, FastInput.ws(bytes("  x"), 0));
		assertEquals(1, FastInput.ws(bytes(" \u00e9"), 0)); // a blank before a non-ASCII byte
		assertEquals(3, FastInput.ws(bytes("a \n" + "b"), 1)); // a blank followed by more whitespace
		assertEquals(4, FastInput.ws(bytes(" \t\r\n1"), 0));
		assertEquals(3, FastInput.ws(bytes("  \n"), 0));
		assertEquals(3, FastInput.ws(bytes("ab \f"), 2)); // the space is skipped, the form feed is not JSON whitespace
		assertEquals(0, FastInput.ws(bytes("\u000b"), 0));
		assertEquals(0, FastInput.ws(bytes("\u00a0"), 0)); // no-break space
	}

	/** Reference for plainStringEnd: the plainest possible loop. */
	private static int plainStringEndReference(byte[] b, int s) {
		for (int i = s; i < b.length; i++) {
			int c = b[i] & 0xff;
			if (c == '"') {
				return i;
			}
			if (c == '\\' || c < 0x20 || c >= 0x80) {
				return -1;
			}
		}
		return -1;
	}

	@Test
	void plainStringEndAgreesWithTheReferenceEverywhere() {
		Random rng = new Random(11);
		int[] specials = {'"', '\\', 0, 1, 0x1f, 0x20, 0x21, 0x7e, 0x7f, 0x80, 0x81, 0xc3, 0xff};
		for (int round = 0; round < 200_000; round++) {
			int length = rng.nextInt(40);
			byte[] b = new byte[length];
			for (int i = 0; i < length; i++) {
				b[i] = (byte) (0x20 + rng.nextInt(0x5f)); // printable ASCII, may include '"' or '\\'
				if (b[i] == '"' || b[i] == '\\') {
					b[i] = 'a';
				}
			}
			// sprinkle 0-3 special bytes
			for (int k = rng.nextInt(4); k > 0 && length > 0; k--) {
				b[rng.nextInt(length)] = (byte) specials[rng.nextInt(specials.length)];
			}
			int start = length == 0 ? 0 : rng.nextInt(length + 1);
			assertEquals(plainStringEndReference(b, start), FastInput.plainStringEnd(b, start),
					"bytes " + java.util.HexFormat.of().formatHex(b) + " from " + start);
		}
	}

	@Test
	void plainStringEndFindsTheFirstSpecialByteAtEveryPosition() {
		// a special byte at each of 40 positions, alone and behind a quote, across word
		// boundaries
		for (int special : new int[]{'\\', 0x00, 0x1f, 0x80, 0xff}) {
			for (int at = 0; at < 40; at++) {
				byte[] b = new byte[41];
				java.util.Arrays.fill(b, (byte) 'a');
				b[at] = (byte) special;
				b[40] = '"';
				assertEquals(-1, FastInput.plainStringEnd(b, 0), "special " + special + " at " + at);
			}
		}
		for (int at = 0; at < 40; at++) {
			byte[] b = new byte[41];
			java.util.Arrays.fill(b, (byte) 'a');
			b[at] = '"';
			b[40] = '\\';
			assertEquals(at, FastInput.plainStringEnd(b, 0), "quote at " + at);
		}
		assertEquals(-1, FastInput.plainStringEnd(bytes("abc"), 0), "unterminated");
		assertEquals(-1, FastInput.plainStringEnd(bytes(""), 0));
		assertEquals(0, FastInput.plainStringEnd(bytes("\""), 0));
	}

	@Test
	void straightLineAccessorsAndTheDepthBound() {
		byte[] whole = bytes("{}");
		FastInput in = new FastInput(whole, 0, whole.length);
		assertSame(whole, in.array());
		assertEquals(0, in.pos());
		in.pos(1);
		assertEquals(1, in.pos());
		// over a slice the array is the whole array; finished() catches a parse that
		// ran past the slice
		byte[] padded = bytes("xx{}yy");
		FastInput slice = new FastInput(padded, 2, 2);
		assertSame(padded, slice.array());
		assertEquals(2, slice.pos());
		slice.pos(4);
		assertTrue(slice.finished());
		slice.pos(5);
		assertFalse(slice.finished(), "ended beyond the slice");
		FastInput deep = new FastInput(whole, 0, whole.length);
		for (int i = 0; i < 100; i++) {
			deep.enter();
		}
		assertThrows(FastInput.Bail.class, deep::enter);
		deep.leave();
		deep.enter(); // one level back
		assertFalse(new FastInput(bytes("{} \n"), 0, 4).finished(), "position 0 is not the end");
		FastInput end = new FastInput(bytes("{} \n"), 0, 4);
		end.pos(2);
		assertTrue(end.finished());
		end.pos(1);
		assertFalse(end.finished());
	}

	@Test
	void structureAndWhitespace() {
		FastInput in = input(" \n{ \"a\" : [ 1 , 2 ] ,\t\"b\":{} }  ");
		in.objectStart();
		assertEquals("a", in.nameString());
		in.arrayStart();
		assertEquals(1, in.int32());
		assertTrue(in.comma());
		assertEquals(2, in.int32());
		assertFalse(in.comma());
		in.arrayEndRequired();
		assertTrue(in.comma());
		assertEquals("b", in.nameString());
		in.objectStart();
		assertTrue(in.objectEnd());
		assertFalse(in.comma());
		in.objectEndRequired();
		assertTrue(in.atEnd());
	}

	@Test
	void structureErrorsBail() {
		assertBails("[1]", in -> {
			in.objectStart();
			return null;
		});
		assertBails("{\"a\":1", in -> {
			in.objectStart();
			in.nameString();
			in.int32();
			in.objectEndRequired();
			return null;
		});
		assertBails("{\"a\" 1}", in -> {
			in.objectStart();
			return in.nameString();
		});
		assertBails("{a:1}", in -> {
			in.objectStart();
			return in.nameString();
		});
		assertBails("{'a':1}", in -> {
			in.objectStart();
			return in.nameString();
		});
		assertBails("{\"a\\u0061\":1}", in -> {
			in.objectStart();
			return in.nameString(); // escaped member names are left to the general reader
		});
	}

	@Test
	void skipValueAcceptsAnyValidValueAndRejectsTheRest() {
		for (String ok : new String[]{"1", "-1.5e3", "\"x\"", "\"\\u00e9\\n\"", "\"\u00e9\"", "true", "false", "null",
				"[]", "{}", "[1,[2,[3]],{\"a\":[null]}]", "{\"a\":{\"b\":{\"c\":[1,2,3]}},\"d\":\"e\"}",
				" [ 1 , 2 ] "}) {
			FastInput in = input(ok);
			in.skipValue();
			assertTrue(in.atEnd(), ok);
		}
		for (String bad : new String[]{"[1,]", "[,1]", "{\"a\":}", "{\"a\"}", "{,}", "[1 2]", "tru", "nul", "01", "[",
				"{", "\"abc", "{\"a\":1,}", "[1}", "{\"a\":1]", "\u0001", "&", "1e", "-"}) {
			assertBails(bad, in -> {
				in.skipValue();
				return null;
			});
		}
		// nesting deeper than the limit is not skipped (recursion guard)
		String deep = "[".repeat(100) + "]".repeat(100);
		assertBails(deep, in -> {
			in.skipValue();
			return null;
		});
		String shallow = "[".repeat(50) + "]".repeat(50);
		FastInput in = input(shallow);
		in.skipValue();
		assertTrue(in.atEnd());
	}

	@Test
	void nullAndBoolLiterals() {
		Boolean parsedNull = readAll("null", FastInput::nullLiteral);
		assertTrue(parsedNull);
		assertFalse(input("nul").nullLiteral());
		assertFalse(input("nullx").nullLiteral());
		assertFalse(input("null1").nullLiteral());
		assertFalse(input("Null").nullLiteral());
		assertEquals(true, readAll("true", FastInput::bool));
		assertEquals(false, readAll("false", FastInput::bool));
		for (String s : new String[]{"True", "TRUE", "tru", "truee", "falsey", "1", "\"true\"", "t", "yes"}) {
			assertBails(s, FastInput::bool);
		}
	}
}
