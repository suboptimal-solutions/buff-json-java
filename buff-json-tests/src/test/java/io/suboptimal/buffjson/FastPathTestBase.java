package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

import com.alibaba.fastjson2.JSONException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf_test_messages.proto3.TestMessagesProto3.TestAllTypesProto3;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.internal.FastInput;
import io.suboptimal.buffjson.proto.*;

/**
 * Differential tests for the canonical-input fast path.
 *
 * <p>
 * The fast path is only allowed to be a faster way to reach the answer the
 * general decoder gives. This test class checks that from three directions:
 *
 * <ul>
 * <li><b>coverage</b> - canonical output of {@code JsonFormat} and of BuffJson
 * itself is accepted by {@code readFast} without bailing (otherwise the fast
 * path would silently never be used) and decodes to the exact original message;
 * <li><b>equivalence</b> - for arbitrary damaged, reordered, respelled or
 * truncated documents, decoding with the fast path enabled gives the same
 * message, or the same exception type, as with it disabled, and whenever
 * {@code readFast} accepts a document the general decoder accepts it with an
 * equal result;
 * <li><b>String input</b> - Latin-1 backing arrays are read without being
 * decoded as UTF-8, and Strings that are not Latin-1 coded never reach the byte
 * cursor.
 * </ul>
 */
abstract class FastPathTestBase {

	private static final BuffJsonDecoder RUNTIME = BuffJson.decoder().setGeneratedDecoders(false);

	private static final JsonFormat.Printer COMPACT = JsonFormat.printer().omittingInsignificantWhitespace();
	private static final JsonFormat.Printer PRETTY = JsonFormat.printer();
	private static final BuffJsonEncoder ENCODER = BuffJson.encoder();

	private static final List<Message> TYPES = List.of(TestAllScalars.getDefaultInstance(),
			TestRepeatedScalars.getDefaultInstance(), TestNesting.getDefaultInstance(),
			TestRecursive.getDefaultInstance(), TestOneof.getDefaultInstance(), TestMaps.getDefaultInstance(),
			TestWrappers.getDefaultInstance(), TestTimestamp.getDefaultInstance(), TestDuration.getDefaultInstance(),
			TestOptionalFields.getDefaultInstance(), TestCustomJsonName.getDefaultInstance(),
			TestEscapedJsonNames.getDefaultInstance(), TestDeprecatedFields.getDefaultInstance(),
			TestNameCollisions.getDefaultInstance(), TestNameLengths.getDefaultInstance(),
			TestAllTypesProto3.getDefaultInstance());

	private static final Set<String> SKIP_UNSUPPORTED = RandomProtoMessages.UNSUPPORTED_BY_FAST_PATH;
	private static final Set<String> SKIP_ANY = Set.of("google.protobuf.Any");

	// ------------------------------------------------------------------ helpers

	private record Outcome(Message message, String failure) {
		static Outcome of(Supplier<Message> action) {
			try {
				return new Outcome(action.get(), null);
			} catch (JSONException e) {
				return new Outcome(null, "JSONException");
			} catch (Throwable t) {
				return new Outcome(null, t.getClass().getName());
			}
		}

		@Override
		public String toString() {
			return failure != null ? "throws " + failure : String.valueOf(message).replace('\n', ' ');
		}
	}

	/**
	 * The decoder whose fast path is under test: the one that reads through the
	 * kind of generated decoder the subclass is about.
	 */
	protected abstract BuffJsonDecoder fast();

	/**
	 * The general decoder of the same tier, with the fast path off: what the fast
	 * path must agree with. (The tiers differ in a few leniencies the fast path
	 * must not change: a null element of a repeated field is skipped by the runtime
	 * decoder and rejected by the plugin's.)
	 */
	protected abstract BuffJsonDecoder general();

	/**
	 * Runs the {@code readFast} of that kind of decoder on the whole array; null if
	 * it bailed or left input unread.
	 */
	protected abstract Message readFast(Message defaultInstance, byte[] json, boolean latin1);

	/**
	 * Whether this kind of decoder exists on this JVM; otherwise the tests are
	 * skipped.
	 */
	protected boolean available() {
		return true;
	}

	@BeforeEach
	void skipWhenUnavailable() {
		Assumptions.assumeTrue(available(), "not available on this JVM");
	}

	/** Runs {@code readFast} directly; null if it bailed or left input unread. */
	private Message tryFast(Message defaultInstance, byte[] json, boolean latin1) {
		return readFast(defaultInstance, json, latin1);
	}

	/** {@code readFast} of the decoder the plugin generated, for comparisons. */
	protected static Message readFastCompiled(Message defaultInstance, byte[] json, boolean latin1) {
		BuffJsonGeneratedDecoder<?> decoder = ((BuffJsonCodecHolder) defaultInstance).buffJsonDecoder();
		return run(decoder, json, latin1);
	}

	protected static Message run(BuffJsonGeneratedDecoder<?> decoder, byte[] json, boolean latin1) {
		try {
			FastInput in = new FastInput(json, 0, json.length, latin1);
			Message message = decoder.readFast(in);
			return in.finished() ? message : null;
		} catch (FastInput.Bail | IndexOutOfBoundsException bail) {
			return null;
		}
	}

	/**
	 * Types with custom json_names that JsonFormat prints unescaped (invalid JSON);
	 * BuffJson escapes them.
	 */
	private static final Set<String> JSON_FORMAT_UNSAFE = Set.of("io.suboptimal.buffjson.proto.TestEscapedJsonNames",
			"io.suboptimal.buffjson.proto.TestNameCollisions");

	/** The spellings a producer would emit for {@code message}. */
	private static List<String> canonicalForms(Message message) throws Exception {
		List<String> forms = new ArrayList<>();
		forms.add(ENCODER.encode(message));
		if (!JSON_FORMAT_UNSAFE.contains(name(message))) {
			forms.add(COMPACT.print(message));
			forms.add(PRETTY.print(message));
		}
		return forms;
	}

	private static String name(Message m) {
		return m.getDescriptorForType().getFullName();
	}

	// --------------------------------------------------------------- coverage

	@Test
	void canonicalOutputIsAcceptedByTheFastPathAndExact() throws Exception {
		int checked = 0;
		for (Message type : TYPES) {
			Random rng = new Random(0xC0FFEE ^ name(type).hashCode());
			for (int i = 0; i < 250; i++) {
				Message message = RandomProtoMessages.generate(type, rng, 3, SKIP_UNSUPPORTED);
				for (String json : canonicalForms(message)) {
					byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
					Message fast = tryFast(type, bytes, false);
					if (fast != null || !JSON_FORMAT_UNSAFE.contains(name(type))) {
						assertNotNull(fast, "readFast bailed on canonical " + name(type) + " input: " + json);
						assertEquals(message, fast, "readFast result for " + json);
					} // else: member names with escapes or non-ASCII text are left to the general
						// decoder
					assertEquals(message, fast().decode(bytes, type.getClass()), json);
					assertEquals(message, fast().decode(json, type.getClass()), json);
					checked++;
				}
			}
		}
		assertTrue(checked > 10_000, "checked " + checked);
	}

	@Test
	void protoFieldNamesAreAcceptedByTheFastPathToo() throws Exception {
		// what producers configured to keep proto names (snake_case) write
		JsonFormat.Printer protoNames = JsonFormat.printer().omittingInsignificantWhitespace()
				.preservingProtoFieldNames();
		int snakeCase = 0;
		for (Message type : TYPES) {
			if (JSON_FORMAT_UNSAFE.contains(name(type))) {
				continue;
			}
			Random rng = new Random(0x5AFE ^ name(type).hashCode());
			for (int i = 0; i < 100; i++) {
				Message message = RandomProtoMessages.generate(type, rng, 3, SKIP_UNSUPPORTED);
				String json = protoNames.print(message);
				snakeCase += json.contains("_") ? 1 : 0;
				Message fast = tryFast(type, json.getBytes(StandardCharsets.UTF_8), false);
				assertNotNull(fast, "readFast bailed on proto-named " + name(type) + " input: " + json);
				assertEquals(message, fast, json);
			}
		}
		assertTrue(snakeCase > 100, "the fixtures should contain snake_case names, saw " + snakeCase);
	}

	@Test
	void everySingleByteOfAMemberNameMayBeDamagedWithoutChangingTheOutcome() throws Exception {
		// names of every length 1..50, prefix collisions, aliases, custom and escaped
		// json_names:
		// changing any one byte of `"name":` must give what the general decoder gives
		// -- the
		// byte-exact matcher may only ever accept the exact spelling
		int checked = 0;
		for (Message type : List.of(TestNameLengths.getDefaultInstance(), TestNameCollisions.getDefaultInstance())) {
			for (var fd : type.getDescriptorForType().getFields()) {
				for (String name : new String[]{fd.getJsonName(), fd.getName()}) {
					String pattern = "\"" + name + "\":";
					for (int at = 0; at < pattern.length(); at++) {
						for (char replacement : new char[]{'Q', '"', ' ', '0', '_'}) {
							if (pattern.charAt(at) == replacement) {
								continue;
							}
							String damaged = pattern.substring(0, at) + replacement + pattern.substring(at + 1);
							String json = "{" + damaged + "1}";
							byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
							Class<? extends Message> cls = type.getClass();
							Outcome general = Outcome.of(() -> general().decode(bytes, cls));
							Outcome fast = Outcome.of(() -> fast().decode(bytes, cls));
							assertEquals(general, fast, json);
							Message direct = tryFast(type, bytes, false);
							if (direct != null) {
								assertNull(general.failure(),
										"readFast accepted what the general decoder rejects: " + json);
								assertEquals(general.message(), direct, json);
							}
							checked++;
						}
					}
				}
			}
		}
		assertTrue(checked > 5000, "checked " + checked);
	}

	@Test
	void unsupportedWellKnownTypesFallBackToTheSameResult() throws Exception {
		Message type = TestAllTypesProto3.getDefaultInstance();
		Random rng = new Random(77);
		int bailed = 0;
		for (int i = 0; i < 400; i++) {
			// Struct / Value / ListValue / FieldMask members are present here
			Message message = RandomProtoMessages.generate(type, rng, 3, SKIP_ANY);
			String json = COMPACT.print(message);
			byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
			assertEquals(message, general().decode(bytes, TestAllTypesProto3.class), json);
			assertEquals(message, fast().decode(bytes, TestAllTypesProto3.class), json);
			Message direct = tryFast(type, bytes, false);
			if (direct == null) {
				bailed++;
			} else {
				assertEquals(message, direct, json);
			}
		}
		assertTrue(bailed > 0, "expected the unsupported members to make readFast bail sometimes");
	}

	// ------------------------------------------------------------ equivalence

	private static byte[] mutate(byte[] json, Random rng) {
		byte[] structural = "\"\\,:{}[] tnfu0-+.eE1\u00c3\u0080\u00ff".getBytes(StandardCharsets.ISO_8859_1);
		List<Byte> out = new ArrayList<>(json.length + 8);
		for (byte b : json) {
			out.add(b);
		}
		int edits = 1 + rng.nextInt(3);
		for (int e = 0; e < edits && !out.isEmpty(); e++) {
			int at = rng.nextInt(out.size());
			switch (rng.nextInt(7)) {
				case 0 -> out.set(at, (byte) rng.nextInt(256));
				case 1 -> out.set(at, structural[rng.nextInt(structural.length)]);
				case 2 -> out.remove(at);
				case 3 -> out.add(at, structural[rng.nextInt(structural.length)]);
				case 4 -> {
					if (at + 1 < out.size()) {
						byte t = out.get(at);
						out.set(at, out.get(at + 1));
						out.set(at + 1, t);
					}
				}
				case 5 -> { // duplicate a short span
					int len = Math.min(1 + rng.nextInt(8), out.size() - at);
					out.addAll(at, new ArrayList<>(out.subList(at, at + len)));
				}
				default -> { // truncate
					if (rng.nextInt(4) == 0) {
						out = new ArrayList<>(out.subList(0, at));
					}
				}
			}
		}
		byte[] result = new byte[out.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = out.get(i);
		}
		return result;
	}

	@Test
	void damagedInputBehavesExactlyLikeTheGeneralDecoder() throws Exception {
		long comparisons = 0;
		long accepted = 0;
		for (Message type : TYPES) {
			Class<? extends Message> cls = type.getClass();
			Random rng = new Random(0xBADC0DE ^ name(type).hashCode());
			for (int i = 0; i < 120; i++) {
				Message message = RandomProtoMessages.generate(type, rng, 3, SKIP_UNSUPPORTED);
				byte[] original = ENCODER.encode(message).getBytes(StandardCharsets.UTF_8);
				for (int k = 0; k < 60; k++) {
					byte[] mutated = k == 0 ? original : mutate(original, rng);
					Outcome general = Outcome.of(() -> general().decode(mutated, cls));
					Outcome fast = Outcome.of(() -> fast().decode(mutated, cls));
					assertEquals(general, fast,
							name(type) + " input: " + new String(mutated, StandardCharsets.ISO_8859_1));
					// whenever the byte cursor itself accepts a document, the general decoder must
					// accept it too, with an equal result (it may never accept more)
					Message direct = tryFast(type, mutated, false);
					if (direct != null) {
						accepted++;
						assertNull(general.failure(), "readFast accepted what the general decoder rejects: "
								+ new String(mutated, StandardCharsets.ISO_8859_1));
						assertEquals(general.message(), direct,
								"readFast result differs for " + new String(mutated, StandardCharsets.ISO_8859_1));
					}
					// the same bytes as a Latin-1 String: byte >= 0x80 is a character, not UTF-8
					String asLatin1 = new String(mutated, StandardCharsets.ISO_8859_1);
					Outcome generalString = Outcome.of(() -> general().decode(asLatin1, cls));
					Outcome fastString = Outcome.of(() -> fast().decode(asLatin1, cls));
					assertEquals(generalString, fastString, name(type) + " String input: " + asLatin1);
					comparisons += 2;
				}
			}
		}
		assertTrue(comparisons > 100_000, "compared " + comparisons);
		assertTrue(accepted > 20_000,
				"the mutation fuzz should regularly produce documents readFast accepts, got " + accepted);
	}

	@Test
	void typedRuntimeDecoderAgreesWithBothOnCanonicalInput() throws Exception {
		Message type = TestAllTypesProto3.getDefaultInstance();
		Random rng = new Random(99);
		for (int i = 0; i < 300; i++) {
			Message message = RandomProtoMessages.generate(type, rng, 3, SKIP_ANY);
			byte[] bytes = COMPACT.print(message).getBytes(StandardCharsets.UTF_8);
			assertEquals(RUNTIME.decode(bytes, TestAllTypesProto3.class),
					fast().decode(bytes, TestAllTypesProto3.class));
		}
	}

	// ------------------------------------------------------- respelled documents

	@Test
	void everyKindOfNonCanonicalSpellingGivesTheGeneralResult() {
		String[] documents = {
				// numbers
				"{\"optionalInt32\":1.0}", "{\"optionalInt32\":1.5}", "{\"optionalInt32\":\"1\"}",
				"{\"optionalInt32\":01}", "{\"optionalInt32\":+1}", "{\"optionalInt32\":1e2}", "{\"optionalInt32\":-0}",
				"{\"optionalInt32\":2147483648}", "{\"optionalInt32\":true}", "{\"optionalInt32\":\"\"}",
				"{\"optionalInt32\":null}", "{\"optionalInt32\":[1]}", "{\"optionalInt64\":1}",
				"{\"optionalInt64\":\"1\"}", "{\"optionalInt64\":\"9223372036854775807\"}",
				"{\"optionalInt64\":\"9223372036854775808\"}", "{\"optionalInt64\":1.0}", "{\"optionalInt64\":\"1.0\"}",
				"{\"optionalUint64\":\"18446744073709551615\"}", "{\"optionalUint64\":18446744073709551615}",
				"{\"optionalUint64\":\"18446744073709551616\"}", "{\"optionalUint32\":4294967295}",
				"{\"optionalUint32\":4294967296}", "{\"optionalUint32\":-1}", "{\"optionalDouble\":1e400}",
				"{\"optionalDouble\":-1e400}", "{\"optionalDouble\":1e-400}", "{\"optionalDouble\":\"NaN\"}",
				"{\"optionalDouble\":NaN}", "{\"optionalDouble\":\"1.5\"}", "{\"optionalDouble\":.5}",
				"{\"optionalDouble\":5.}", "{\"optionalDouble\":0.30000000000000004}",
				"{\"optionalFloat\":3.4028235e38}", "{\"optionalFloat\":3.4028236e38}", "{\"optionalFloat\":1e39}",
				"{\"optionalFloat\":16777217}", "{\"optionalFloat\":0.1}", "{\"optionalFloat\":\"-Infinity\"}",
				// strings and bytes
				"{\"optionalString\":\"a\\u0000b\"}", "{\"optionalString\":\"\\ud800\"}",
				"{\"optionalString\":\"\\ud83d\\ude00\"}", "{\"optionalString\":\"\\q\"}", "{\"optionalString\":1}",
				"{\"optionalString\":null}", "{\"optionalString\":\"tab\there\"}", "{\"optionalString\":'single'}",
				"{\"optionalBytes\":\"AAA\"}", "{\"optionalBytes\":\"AAAA\"}", "{\"optionalBytes\":\"-_-_\"}",
				"{\"optionalBytes\":\"AA==\"}", "{\"optionalBytes\":\"A\"}", "{\"optionalBytes\":\"AA AA\"}",
				"{\"optionalBytes\":1}",
				// enums
				"{\"optionalNestedEnum\":\"FOO\"}", "{\"optionalNestedEnum\":\"foo\"}",
				"{\"optionalNestedEnum\":\"NOPE\"}", "{\"optionalNestedEnum\":1}", "{\"optionalNestedEnum\":-1}",
				"{\"optionalNestedEnum\":12345}", "{\"optionalNestedEnum\":1.0}", "{\"optionalNestedEnum\":null}",
				"{\"optionalNestedEnum\":\"\"}",
				// messages and containers
				"{\"optionalNestedMessage\":null}", "{\"optionalNestedMessage\":{}}",
				"{\"optionalNestedMessage\":{\"a\":1}}", "{\"optionalNestedMessage\":[]}",
				"{\"optionalNestedMessage\":1}", "{\"repeatedInt32\":[1,2,3]}", "{\"repeatedInt32\":[1,null,3]}",
				"{\"repeatedInt32\":[1,\"2\",3]}", "{\"repeatedInt32\":[1,2,]}", "{\"repeatedInt32\":[,1]}",
				"{\"repeatedInt32\":null}", "{\"repeatedInt32\":[]}", "{\"repeatedInt32\":1}",
				"{\"repeatedString\":[\"a\",null]}", "{\"mapStringString\":{\"a\":\"b\"}}",
				"{\"mapStringString\":{\"a\":null}}", "{\"mapStringString\":{a:\"b\"}}",
				"{\"mapInt32Int32\":{\"1\":2}}", "{\"mapInt32Int32\":{\"01\":2}}", "{\"mapInt32Int32\":{\"1.0\":2}}",
				"{\"mapBoolBool\":{\"true\":false}}", "{\"mapBoolBool\":{\"TRUE\":false}}",
				"{\"mapInt64Int64\":{\"9223372036854775808\":1}}",
				// well-known types
				"{\"optionalTimestamp\":\"2024-01-01T00:00:00Z\"}",
				"{\"optionalTimestamp\":\"2024-01-01T00:00:00.5Z\"}",
				"{\"optionalTimestamp\":\"2024-01-01T00:00:00+01:00\"}",
				"{\"optionalTimestamp\":\"2024-01-01t00:00:00z\"}", "{\"optionalTimestamp\":\"0000-01-01T00:00:00Z\"}",
				"{\"optionalTimestamp\":1}", "{\"optionalTimestamp\":null}", "{\"optionalDuration\":\"1s\"}",
				"{\"optionalDuration\":\"1.5s\"}", "{\"optionalDuration\":\"-0.5s\"}",
				"{\"optionalDuration\":\"1.1234567890s\"}", "{\"optionalDuration\":\"315576000001s\"}",
				"{\"optionalDuration\":1}", "{\"optionalBoolWrapper\":true}", "{\"optionalInt32Wrapper\":1}",
				"{\"optionalInt32Wrapper\":\"1\"}", "{\"optionalInt32Wrapper\":null}",
				"{\"optionalStringWrapper\":\"x\"}", "{\"optionalBytesWrapper\":\"AAAA\"}",
				"{\"optionalInt64Wrapper\":\"5\"}", "{\"optionalUint64Wrapper\":\"18446744073709551615\"}",
				"{\"optionalDoubleWrapper\":\"NaN\"}", "{\"optionalFloatWrapper\":1.5}",
				"{\"optionalStruct\":{\"a\":1}}", "{\"optionalValue\":1}", "{\"optionalValue\":null}",
				"{\"optionalFieldMask\":\"a,b\"}",
				// structure
				"{}", " { } ", "{\"optionalInt32\":1,\"optionalInt32\":2}", "{\"optionalInt32\":1,}",
				"{,\"optionalInt32\":1}", "{\"optionalInt32\" : 1}",
				"{ \"optionalInt32\":1 , \"optionalInt64\" : \"2\" }", "{\"unknown\":1}",
				"{\"unknown\":{\"a\":[1,2,{\"b\":null}]},\"optionalInt32\":3}", "{\"optional_int32\":4}",
				"{\"optionalInt32\":1}x", "{\"optionalInt32\":1}{}", "{\"optionalInt32\":1", "{\"optionalInt32\"",
				"{\"optionalInt32\":", "{\"optionalInt3", "{\"", "{", "}", "[]", "[{}]", "null", "true", "1", "\"x\"",
				"", "   ", "\ufeff{}", "{}\u0000", "{\"a\":1}//c", "/*c*/{}", "{'optionalInt32':1}",
				"{optionalInt32:1}", "{\"optionalInt32\":1} ", "\n{\"optionalInt32\":1}\n",
				"{\"oneofUint32\":1,\"oneofString\":\"x\"}", "{\"oneofNestedMessage\":{\"a\":1},\"oneofEnum\":\"BAR\"}",
				"{\"\\u006fptionalInt32\":5}", "{\"optionalInt32\":5,\"\\u006fptionalInt64\":\"6\"}"};
		for (String json : documents) {
			for (boolean asString : new boolean[]{false, true}) {
				Outcome general = Outcome.of(() -> asString
						? general().decode(json, TestAllTypesProto3.class)
						: general().decode(json.getBytes(StandardCharsets.UTF_8), TestAllTypesProto3.class));
				Outcome fast = Outcome.of(() -> asString
						? fast().decode(json, TestAllTypesProto3.class)
						: fast().decode(json.getBytes(StandardCharsets.UTF_8), TestAllTypesProto3.class));
				assertEquals(general, fast, (asString ? "String " : "bytes ") + "input: " + json);
			}
			Message direct = tryFast(TestAllTypesProto3.getDefaultInstance(), json.getBytes(StandardCharsets.UTF_8),
					false);
			if (direct != null) {
				Outcome general = Outcome
						.of(() -> general().decode(json.getBytes(StandardCharsets.UTF_8), TestAllTypesProto3.class));
				assertNull(general.failure(), "readFast accepted what the general decoder rejects: " + json);
				assertEquals(general.message(), direct, json);
			}
		}
	}

	// --------------------------------------------------------- String input

	@Test
	void latin1BytesAreCharactersNotUtf8() {
		// "Ã©" are the two Latin-1 characters C3 A9. Read as UTF-8 the same bytes are
		// "é".
		String json = "{\"optionalString\":\"\u00c3\u00a9\"}";
		TestAllTypesProto3 expected = TestAllTypesProto3.newBuilder().setOptionalString("\u00c3\u00a9").build();
		assertEquals(expected, general().decode(json, TestAllTypesProto3.class));
		assertEquals(expected, fast().decode(json, TestAllTypesProto3.class));
		// and the genuine UTF-8 encoding of "é" still decodes as "é" from bytes
		TestAllTypesProto3 accented = TestAllTypesProto3.newBuilder().setOptionalString("\u00e9").build();
		assertEquals(accented, fast().decode("{\"optionalString\":\"\u00e9\"}".getBytes(StandardCharsets.UTF_8),
				TestAllTypesProto3.class));
	}

	@Test
	void stringsThatAreNotLatin1KeepTheirCharactersIncludingLoneSurrogates() {
		String[] values = {"\u65e5\u672c\u8a9e", "\ud83d\ude00", "\ud800", "a\udc00b", "\ud83dx", "caf\u00e9 \u65e5"};
		for (String value : values) {
			String json = "{\"optionalString\":\"" + value + "\"}";
			Outcome general = Outcome.of(() -> general().decode(json, TestAllTypesProto3.class));
			Outcome fast = Outcome.of(() -> fast().decode(json, TestAllTypesProto3.class));
			assertEquals(general, fast, "value " + value.chars().mapToObj(c -> String.format("\\u%04x", c)).toList());
			if (general.message() != null) {
				assertEquals(value, ((TestAllTypesProto3) general.message()).getOptionalString());
			}
		}
	}

	@Test
	void fastPathCanBeDisabledAndIsIgnoredWithoutAnyGeneratedDecoder() {
		TestAllTypesProto3 message = TestAllTypesProto3.newBuilder().setOptionalInt32(5).setOptionalString("x").build();
		String json = compactPrint(message);
		assertTrue(fast().getFastPath());
		assertFalse(general().getFastPath());
		assertEquals(message, fast().decode(json, TestAllTypesProto3.class));
		assertEquals(message, general().decode(json, TestAllTypesProto3.class));
		assertEquals(message, BuffJson.decoder().setGeneratedDecoders(false).setRuntimeCodegen(false).decode(json,
				TestAllTypesProto3.class));
		assertEquals(message, BuffJson.decoder().setFastPath(true).setGeneratedDecoders(false).setRuntimeCodegen(false)
				.decode(json.getBytes(StandardCharsets.UTF_8), TestAllTypesProto3.class));
	}

	/**
	 * {@code n} nested TestRecursive objects:
	 * {"value":1,"child":{"value":1,...{}}}.
	 */
	private static String nested(int n) {
		return "{\"value\":1,\"child\":".repeat(n - 1) + "{\"value\":1}" + "}".repeat(n - 1);
	}

	@Test
	void nestingDeeperThanTheCursorAllowsIsLeftToTheGeneralDecoder() {
		// FastInput reads at most 100 nested objects; recursion in readFast is bounded
		// by that, not by what a client sends. Beyond it the general decoder answers.
		Message type = TestRecursive.getDefaultInstance();
		assertNotNull(tryFast(type, nested(100).getBytes(StandardCharsets.UTF_8), false), "100 levels are read");
		assertNull(tryFast(type, nested(101).getBytes(StandardCharsets.UTF_8), false), "101 levels bail");
		for (int n : new int[]{1, 2, 50, 99, 100, 101, 150, 500, 1500}) {
			String json = nested(n);
			TestRecursive expected = general().decode(json, TestRecursive.class);
			assertEquals(expected, fast().decode(json, TestRecursive.class), "String, " + n + " levels");
			assertEquals(expected, fast().decode(json.getBytes(StandardCharsets.UTF_8), TestRecursive.class),
					"bytes, " + n + " levels");
		}
	}

	private static String compactPrint(Message message) {
		try {
			return COMPACT.print(message);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	@Test
	void slicesAreDecodedInPlace() throws Exception {
		TestAllTypesProto3 message = TestAllTypesProto3.newBuilder().setOptionalInt32(7).setOptionalString("slice")
				.setOptionalNestedMessage(TestAllTypesProto3.NestedMessage.newBuilder().setA(3)).build();
		byte[] json = COMPACT.print(message).getBytes(StandardCharsets.UTF_8);
		byte[] padded = new byte[json.length + 10];
		System.arraycopy(json, 0, padded, 5, json.length);
		java.util.Arrays.fill(padded, 0, 5, (byte) 'x');
		java.util.Arrays.fill(padded, json.length + 5, padded.length, (byte) 'y');
		assertEquals(message, fast().decode(padded, 5, json.length, TestAllTypesProto3.class));
		assertEquals(message, general().decode(padded, 5, json.length, TestAllTypesProto3.class));
		// a slice that ends mid-document must not read past its end
		assertEquals(Outcome.of(() -> general().decode(padded, 5, json.length - 3, TestAllTypesProto3.class)),
				Outcome.of(() -> fast().decode(padded, 5, json.length - 3, TestAllTypesProto3.class)));
		assertEquals(Outcome.of(() -> general().decode(padded, 0, json.length + 5, TestAllTypesProto3.class)),
				Outcome.of(() -> fast().decode(padded, 0, json.length + 5, TestAllTypesProto3.class)));
	}
}
