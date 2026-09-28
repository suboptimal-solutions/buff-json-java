package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import com.alibaba.fastjson2.JSONException;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.proto.AliasedNameEnum;
import io.suboptimal.buffjson.proto.NameEnum;
import io.suboptimal.buffjson.proto.TestNameCollisions;
import io.suboptimal.buffjson.proto.TestNameLengths;

/**
 * Pins the generated decoders' field-name and enum-name resolution.
 *
 * <p>
 * Generated decoders resolve the common spelling of a field name (the exact
 * bytes {@code "jsonName":}) with fastjson2's byte-exact
 * {@code nextIfName4MatchN} family and hand every other spelling -- escapes,
 * whitespace before the colon, proto-name aliases, unknown fields -- to the
 * general {@code readFieldName()} route. These tests check that the fast route
 * is exact (never matches a different name, never skips a name it should have
 * matched) for every name length it supports, on every reader implementation
 * (Latin-1/UTF-8 byte readers and the UTF-16 reader), and that all three decode
 * paths (codegen, typed setters, reflection) stay equivalent on damaged input.
 */
class BuffJsonGeneratedNameMatchTest {

	private static final BuffJsonDecoder CODEGEN = BuffJson.decoder();
	private static final BuffJsonDecoder TYPED = BuffJson.decoder().setGeneratedDecoders(false);
	private static final BuffJsonDecoder REFLECTION = BuffJson.decoder().setGeneratedDecoders(false)
			.setTypedAccessors(false);
	private static final List<BuffJsonDecoder> ALL = List.of(CODEGEN, TYPED, REFLECTION);

	private static final JsonFormat.Printer COMPACT = JsonFormat.printer().omittingInsignificantWhitespace();
	private static final JsonFormat.Printer PRETTY = JsonFormat.printer();

	/**
	 * A prefix that forces the UTF-16 reader: an unknown member with a non-Latin-1
	 * value.
	 */
	private static final String NON_LATIN1_PREFIX = "\"ü\":\"日本\",";

	// --- every name length, every spelling
	// -----------------------------------------

	@Test
	void everyNameLengthMatchesInEveryForm() throws Exception {
		for (FieldDescriptor fd : TestNameLengths.getDescriptor().getFields()) {
			TestNameLengths expected = TestNameLengths.newBuilder().setField(fd, fd.getNumber() * 7 + 1).build();
			for (String json : spellings(fd, expected)) {
				assertAllDecode(expected, json, TestNameLengths.class,
						"field " + fd.getName() + " (length " + fd.getJsonName().length() + ")");
			}
		}
	}

	@Test
	void allNameLengthsTogetherInOrderAndOutOfOrder() throws Exception {
		TestNameLengths.Builder builder = TestNameLengths.newBuilder();
		for (FieldDescriptor fd : TestNameLengths.getDescriptor().getFields()) {
			builder.setField(fd, fd.getNumber() + 100);
		}
		TestNameLengths expected = builder.build();
		String compact = COMPACT.print(expected);
		assertAllDecode(expected, compact, TestNameLengths.class, "in order");
		assertAllDecode(expected, PRETTY.print(expected), TestNameLengths.class, "pretty");

		// Reverse member order: every match has to work without the previous field.
		List<String> members = new ArrayList<>();
		for (FieldDescriptor fd : TestNameLengths.getDescriptor().getFields()) {
			members.add("\"" + fd.getJsonName() + "\":" + (fd.getNumber() + 100));
		}
		java.util.Collections.reverse(members);
		assertAllDecode(expected, "{" + String.join(",", members) + "}", TestNameLengths.class, "reversed");

		// Shuffled, with unknown members of look-alike names between them.
		Random rng = new Random(12345);
		for (int round = 0; round < 25; round++) {
			java.util.Collections.shuffle(members, rng);
			List<String> noisy = new ArrayList<>();
			for (String m : members) {
				noisy.add(lookAlike(m, rng));
				noisy.add(m);
			}
			assertAllDecode(expected, "{" + String.join(",", noisy) + "}", TestNameLengths.class, "shuffled #" + round);
		}
	}

	/**
	 * An unknown member whose name shares a prefix with (or extends) the real one.
	 */
	private static String lookAlike(String member, Random rng) {
		String name = member.substring(1, member.indexOf('"', 1));
		return switch (rng.nextInt(4)) {
			case 0 -> "\"" + name + "y\":5"; // longer than the real name
			case 1 -> "\"" + name.substring(0, name.length() - 1) + "\":6"; // shorter (may be a real name!)
			case 2 -> "\"" + name.substring(0, name.length() - 1) + "Z\":7"; // one byte different at the end
			default -> "\"Z" + name.substring(1) + "\":{\"n\":[1,2,{\"m\":null}]}"; // differs in byte 0
		};
	}

	/**
	 * Names of length {@code n} and {@code n - 1} may both be real fields, so the
	 * shorter look-alike is only unknown when no such field exists; the test uses
	 * values that would corrupt a neighbour if the matcher confused two names.
	 */
	@Test
	void lookAlikeMembersNeverAliasAKnownField() throws Exception {
		// "f11xxxxxxxx" (11) vs "f10xxxxxxx" (10): differ only in a digit
		String json = "{\"f10xxxxxxx\":1,\"f11xxxxxxxx\":2,\"f12xxxxxxxxx\":3}";
		TestNameLengths msg = CODEGEN.decode(json, TestNameLengths.class);
		assertEquals(1, msg.getField(TestNameLengths.getDescriptor().findFieldByName("f10xxxxxxx")));
		assertEquals(2, msg.getField(TestNameLengths.getDescriptor().findFieldByName("f11xxxxxxxx")));
		assertEquals(3, msg.getField(TestNameLengths.getDescriptor().findFieldByName("f12xxxxxxxxx")));

		// Every single-byte substitution inside a name must be "unknown", never a
		// field.
		for (FieldDescriptor fd : TestNameLengths.getDescriptor().getFields()) {
			String name = fd.getJsonName();
			for (int i = 0; i < name.length(); i++) {
				char replacement = name.charAt(i) == 'Q' ? 'R' : 'Q';
				String mutated = name.substring(0, i) + replacement + name.substring(i + 1);
				String probe = "{\"" + mutated + "\":99}";
				for (BuffJsonDecoder decoder : ALL) {
					assertEquals(TestNameLengths.getDefaultInstance(), decoder.decode(probe, TestNameLengths.class),
							"mutated name " + mutated + " must be ignored");
				}
			}
		}
	}

	// --- collisions, aliases and custom json names
	// --------------------------------

	@Test
	void collidingPrefixesAliasesAndCustomNames() throws Exception {
		TestNameCollisions expected = TestNameCollisions.newBuilder().setAbc(1).setAbcd(2).setAbcde(3).setAbcdx(4)
				.setName(5).setNames(6).setNameX(7).setId(8).setIdent(9).setFieldOne(10).setXYZ(11L).setText("t")
				.setKind(NameEnum.NAME_ENUM_FIRST).setCustomAscii(12).setCustomUnicode(13).setCustomSlash(14)
				.setNested(TestNameCollisions.newBuilder().setAbc(17)).build();
		assertAllDecode(expected, COMPACT.print(expected), TestNameCollisions.class, "compact");
		assertAllDecode(expected, PRETTY.print(expected), TestNameCollisions.class, "pretty");
		// JsonFormat does not JSON-escape custom names when printing, so these are
		// hand-written
		TestNameCollisions escaped = TestNameCollisions.newBuilder().setCustomBackslash(15).setCustomQuote(16)
				.setCustomSlash(14).build();
		assertAllDecode(escaped, "{\"back\\\\slash\":15,\"quo\\\"te\":16,\"p/q\":14}", TestNameCollisions.class,
				"escaped custom names");
		assertAllDecode(escaped, "{\"back\\u005cslash\":15,\"quo\\u0022te\":16,\"p\\/q\":14}", TestNameCollisions.class,
				"custom names with unicode/solidus escapes");
		// proto (snake_case) names are accepted as aliases of the lowerCamel JSON names
		String aliased = COMPACT.print(expected).replace("\"fieldOne\"", "\"field_one\"")
				.replace("\"xYZ\"", "\"x_y_z\"").replace("\"nameX\"", "\"name_x\"");
		assertNotEquals(COMPACT.print(expected), aliased);
		assertAllDecode(expected, aliased, TestNameCollisions.class, "aliased");
		// both spellings present: last one wins on every path
		assertAllDecode(TestNameCollisions.newBuilder().setFieldOne(2).build(), "{\"fieldOne\":1,\"field_one\":2}",
				TestNameCollisions.class, "alias after name");
		assertAllDecode(TestNameCollisions.newBuilder().setFieldOne(1).build(), "{\"field_one\":2,\"fieldOne\":1}",
				TestNameCollisions.class, "name after alias");
	}

	// --- damaged input: fast route must agree with the general route
	// ---------------

	@Test
	void mutatedAndTruncatedInputAgreesAcrossPaths() throws Exception {
		TestNameCollisions message = TestNameCollisions.newBuilder().setAbc(1).setAbcd(2).setName(5).setId(8)
				.setFieldOne(10).setText("téx").setKind(NameEnum.NAME_ENUM_SECOND).addKinds(NameEnum.NAME_ENUM_FIRST)
				.putKindByName("k", NameEnum.NAME_ENUM_NEGATIVE)
				.setNested(TestNameCollisions.newBuilder().setAbcde(3).setCustomAscii(4)).build();
		String json = COMPACT.print(message);
		int comparisons = 0;
		for (int i = 0; i <= json.length(); i++) {
			// truncation at every offset (the empty prefix is excluded: decode("") returns
			// null
			// by contract while decode(new byte[0]) does not)
			if (i > 0) {
				comparisons += assertSameOutcome(json.substring(0, i), TestNameCollisions.class);
			}
			if (i == json.length()) {
				break;
			}
			for (String replacement : new String[]{"x", "\"", ":", ",", " ", "\\", "{", "}"}) {
				comparisons += assertSameOutcome(json.substring(0, i) + replacement + json.substring(i + 1),
						TestNameCollisions.class);
			}
			for (String insertion : new String[]{" ", "\"", "\\u0041", "\n"}) {
				comparisons += assertSameOutcome(json.substring(0, i) + insertion + json.substring(i),
						TestNameCollisions.class);
			}
			comparisons += assertSameOutcome(json.substring(0, i) + json.substring(i + 1), TestNameCollisions.class);
		}
		assertTrue(comparisons > 1000);
	}

	// --- enum names
	// ---------------------------------------------------------------

	@Test
	void enumNamesNumbersAndAliases() throws Exception {
		TestNameCollisions expected = TestNameCollisions.newBuilder().setKind(NameEnum.NAME_ENUM_NEGATIVE)
				.setAliasedValue(1).addKinds(NameEnum.NAME_ENUM_FIRST).addKinds(NameEnum.NAME_ENUM_SECOND)
				.putKindByName("a", NameEnum.NAME_ENUM_NEGATIVE).putKindByName("b", NameEnum.NAME_ENUM_SECOND)
				.setChoiceKind(NameEnum.NAME_ENUM_SECOND).setMaybeKind(NameEnum.NAME_ENUM_UNSPECIFIED).build();
		String json = "{\"kind\":\"NAME_ENUM_NEGATIVE\",\"aliased\":\"ALIASED_NAME_ENUM_ALSO_A\","
				+ "\"kinds\":[\"NAME_ENUM_FIRST\",2],\"kindByName\":{\"a\":-1,\"b\":\"NAME_ENUM_SECOND\"},"
				+ "\"choiceKind\":\"NAME_ENUM_SECOND\",\"maybeKind\":\"NAME_ENUM_UNSPECIFIED\"}";
		assertAllDecode(expected, json, TestNameCollisions.class, "names and numbers");
		assertEquals(AliasedNameEnum.ALIASED_NAME_ENUM_A,
				CODEGEN.decode("{\"aliased\":\"ALIASED_NAME_ENUM_A\"}", TestNameCollisions.class).getAliased());
		assertEquals(AliasedNameEnum.ALIASED_NAME_ENUM_A,
				CODEGEN.decode("{\"aliased\":\"ALIASED_NAME_ENUM_ALSO_A\"}", TestNameCollisions.class).getAliased());

		// unrecognised numeric values survive (proto3 open enums)
		for (BuffJsonDecoder decoder : ALL) {
			assertEquals(12345, decoder.decode("{\"kind\":12345}", TestNameCollisions.class).getKindValue());
			assertEquals(12345, decoder.decode("{\"kindByName\":{\"x\":12345}}", TestNameCollisions.class)
					.getKindByNameValueOrThrow("x"));
		}

		// null means "absent" for a plain enum field
		for (BuffJsonDecoder decoder : ALL) {
			assertEquals(TestNameCollisions.getDefaultInstance(),
					decoder.decode("{\"kind\":null}", TestNameCollisions.class));
		}
	}

	@Test
	void unknownAndMalformedEnumNamesAreRejectedOnEveryPath() {
		for (String value : new String[]{"\"NAME_ENUM_THIRD\"", "\"name_enum_first\"", "\" NAME_ENUM_FIRST\"",
				"\"NAME_ENUM_FIRST \"", "\"\"", "\"NAME_ENUM_\"", "\"NAME_ENUM_FIRSTX\"", "\"NAME_ENUM_FIRS\"",
				"\"NAME_ENUM_FIRST\\u0000\""}) {
			for (String field : new String[]{"kind", "kinds", "kindByName", "choiceKind", "maybeKind"}) {
				String body = switch (field) {
					case "kinds" -> "[" + value + "]";
					case "kindByName" -> "{\"k\":" + value + "}";
					default -> value;
				};
				String json = "{\"" + field + "\":" + body + "}";
				for (BuffJsonDecoder decoder : ALL) {
					assertThrows(JSONException.class, () -> decoder.decode(json, TestNameCollisions.class),
							json + " must be rejected");
				}
			}
		}
	}

	// --- helpers -----------------------------------------------------------------

	/** The spellings of {@code {"<field>": value}} the decoders must all accept. */
	private static List<String> spellings(FieldDescriptor fd, Message message) throws Exception {
		String compact = COMPACT.print(message);
		String name = fd.getJsonName();
		String value = compact.substring(compact.indexOf(':') + 1, compact.length() - 1);
		List<String> forms = new ArrayList<>();
		forms.add(compact);
		forms.add(PRETTY.print(message));
		forms.add("{\"" + name + "\" :" + value + "}"); // space before colon: general route
		forms.add("{\"" + name + "\": " + value + "}"); // space after colon: fast route, then whitespace
		forms.add("{ \"" + name + "\":\n\t" + value + " }");
		forms.add("{\"" + name + "\":" + value + ",\"unknown\":[1,{\"a\":null}]}");
		forms.add("{\"unknown\":\"x\",\"" + name + "\":" + value + "}");
		String escaped = "\\u" + String.format("%04x", (int) name.charAt(0)) + name.substring(1);
		forms.add("{\"" + escaped + "\":" + value + "}"); // escaped first byte: general route
		forms.add("{\"" + name + "\":" + value + ",\"" + name + "\":" + value + "}"); // duplicate
		return forms;
	}

	/**
	 * Decodes {@code json} with all three paths, from String, UTF-8 bytes and a
	 * String that forces the UTF-16 reader, and checks every result.
	 */
	private static <T extends Message> void assertAllDecode(T expected, String json, Class<T> type, String context)
			throws Exception {
		for (BuffJsonDecoder decoder : ALL) {
			assertEquals(expected, decoder.decode(json, type), context + " / String / " + json);
			assertEquals(expected, decoder.decode(json.getBytes(StandardCharsets.UTF_8), type),
					context + " / bytes / " + json);
			String wide = NON_LATIN1_PREFIX + json.substring(json.indexOf('{') + 1);
			String widened = json.substring(0, json.indexOf('{') + 1) + wide;
			if (!json.trim().equals("{}")) {
				assertEquals(expected, decoder.decode(widened, type), context + " / UTF-16 / " + widened);
			}
		}
		// JsonFormat is the reference for the well-formed, canonical spellings.
		if (!json.contains("field_one") && !json.contains("x_y_z") && !json.contains("name_x") && !json.contains("\\u")
				&& !json.contains("\"unknown\"") && !json.contains("Z\"") && !json.contains("y\":5")
				&& !json.contains("\":6") && !json.contains("\":7")) {
			T viaJsonFormat = parseWithJsonFormat(json, expected);
			if (viaJsonFormat != null) {
				assertEquals(viaJsonFormat, expected, context + " (reference)");
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends Message> T parseWithJsonFormat(String json, T like) {
		try {
			Message.Builder builder = like.newBuilderForType();
			JsonFormat.parser().merge(json, builder);
			return (T) builder.build();
		} catch (Exception unsupportedByReference) {
			return null;
		}
	}

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
	}

	/**
	 * For each reader implementation (String and UTF-8 bytes) all three decode
	 * paths must accept with equal messages, or all reject with the same exception
	 * type. String and byte input are deliberately not compared with each other:
	 * fastjson2's UTF-8 reader accepts an input truncated inside a multi-byte
	 * character as if it were complete, which is independent of how field names are
	 * resolved.
	 */
	private static <T extends Message> int assertSameOutcome(String json, Class<T> type) {
		byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
		Outcome codegen = Outcome.of(() -> CODEGEN.decode(json, type));
		Outcome typed = Outcome.of(() -> TYPED.decode(json, type));
		Outcome reflection = Outcome.of(() -> REFLECTION.decode(json, type));
		assertEquals(typed, codegen, "codegen vs typed for: " + json);
		assertEquals(reflection, typed, "typed vs reflection for: " + json);
		Outcome codegenBytes = Outcome.of(() -> CODEGEN.decode(utf8, type));
		Outcome typedBytes = Outcome.of(() -> TYPED.decode(utf8, type));
		Outcome reflectionBytes = Outcome.of(() -> REFLECTION.decode(utf8, type));
		assertEquals(typedBytes, codegenBytes, "codegen vs typed (bytes) for: " + json);
		assertEquals(reflectionBytes, typedBytes, "typed vs reflection (bytes) for: " + json);
		for (Outcome o : List.of(codegen, codegenBytes)) {
			assertNotEquals(NullPointerException.class.getName(), o.failure(), json);
			assertNotEquals(ArrayIndexOutOfBoundsException.class.getName(), o.failure(), json);
		}
		return 6;
	}
}
