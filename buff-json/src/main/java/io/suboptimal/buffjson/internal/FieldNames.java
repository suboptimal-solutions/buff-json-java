package io.suboptimal.buffjson.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import com.alibaba.fastjson2.JSONWriter;

/**
 * Packs a JSON field name into the {@code long}/{@code int} words consumed by
 * fastjson2's {@code JSONWriter.writeName2Raw(long)} …
 * {@code writeName16Raw(long, long)} family.
 *
 * <h2>Why</h2>
 *
 * The pre-encoded {@code writeNameRaw(byte[])} / {@code writeNameRaw(char[])}
 * path still costs a static field load, an array length read, and an
 * {@code arraycopy} of the name bytes per field — plus an {@code isUTF8()}
 * branch to pick the right array. The {@code writeNameNRaw} family instead
 * takes the name pre-packed into machine words and stores it with one or two
 * {@code Unsafe.putLong} instructions. It is what fastjson2's own ASM/compiled
 * writers use, and it is the main remaining gap to the fastjson2 POJO ceiling.
 *
 * <h2>Layout</h2>
 *
 * The words are simply machine-word reads out of the literal {@code "name":}
 * text, zero-padded. For a name of length {@code n}:
 *
 * <table border="1">
 * <caption>Packed-word layout per name length</caption>
 * <tr>
 * <th>n</th>
 * <th>call</th>
 * <th>words read from {@code "name":} at</th>
 * </tr>
 * <tr>
 * <td>2–7</td>
 * <td>{@code writeName<n>Raw(long)}</td>
 * <td>{@code long} @ 0</td>
 * </tr>
 * <tr>
 * <td>8</td>
 * <td>{@code writeName8Raw(long)}</td>
 * <td>{@code long} @ <b>1</b></td>
 * </tr>
 * <tr>
 * <td>9</td>
 * <td>{@code writeName9Raw(long, int)}</td>
 * <td>{@code long} @ 0, {@code int} @ 8</td>
 * </tr>
 * <tr>
 * <td>10–15</td>
 * <td>{@code writeName<n>Raw(long, long)}</td>
 * <td>{@code long} @ 0, {@code long} @ 8</td>
 * </tr>
 * <tr>
 * <td>16</td>
 * <td>{@code writeName16Raw(long, long)}</td>
 * <td>{@code long} @ <b>1</b>, {@code long} @ <b>9</b></td>
 * </tr>
 * </table>
 *
 * <p>
 * The offset-1 cases (8 and 16) are the two lengths where fastjson2 writes the
 * opening quote separately and stores only the name body.
 *
 * <h2>Not universally usable — see {@link #PACKED_NAMES_SUPPORTED}</h2>
 *
 * This is an undocumented fastjson2 internal, and two things can invalidate it:
 *
 * <ul>
 * <li><b>Byte order.</b> {@code JSONWriterUTF8} stores the word with
 * {@code Unsafe.putLong} (native order), but {@code JSONWriterUTF16} widens it
 * to {@code char}s by extracting bytes at <em>hard-coded little-endian
 * positions</em> ({@code v & 0xFF}, {@code (v & 0xFF00) << 8}, …) with no
 * {@code BIG_ENDIAN} branch. The two cannot both be satisfied by one constant
 * on a big-endian host.
 * <li><b>Quoting.</b> The quote characters are split between us and fastjson2
 * and the split differs per length: we bake both quotes for lengths 2–6 and
 * 9–14, only the opening quote for 7 and 15, and neither for 8 and 16.
 * fastjson2 emits its share from {@code this.quote}, so under
 * {@code JSONWriter.Feature.UseSingleQuotes} a name of length 7 or 15 comes out
 * as {@code "name'} — not merely a feature we ignore, but JSON no parser
 * accepts.
 * </ul>
 *
 * <p>
 * So {@code PACKED_NAMES_SUPPORTED} self-checks the layout at class init, and
 * {@code ProtobufMessageWriter} additionally skips the codegen path for a
 * single-quote writer. Both failures fall back to the {@code writeNameRaw}
 * arrays, which own every byte they emit.
 *
 * <p>
 * <b>Both encodings share the constants.</b> {@code JSONWriterUTF16} widens the
 * same packed ASCII bytes to {@code char}s on the way out, so a packed name
 * needs no {@code isUTF8()} branch at all — unlike the {@code byte[]}/
 * {@code char[]} pair it replaces.
 *
 * <h2>Applicability</h2>
 *
 * {@link #isPackable(String)} gates the fast path: names must be 2–16
 * characters of printable ASCII with nothing needing JSON escaping. Protobuf
 * field names are {@code [A-Za-z_][A-Za-z0-9_]*} and {@code getJsonName()}
 * lowerCamelCases them, so essentially every field qualifies; an explicit
 * {@code [json_name = "…"]} carrying non-ASCII or a quote falls back to the
 * {@code writeNameRaw} arrays, or — when even those cannot represent it, see
 * {@link #isRawWritable} — to {@code writeName(String)}.
 */
public final class FieldNames {

	/** Longest name the {@code writeNameNRaw} family covers. */
	public static final int MAX_PACKED_LENGTH = 16;

	private static final VarHandle LONG_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class,
			ByteOrder.nativeOrder());

	private static final VarHandle INT_VIEW = MethodHandles.byteArrayViewVarHandle(int[].class,
			ByteOrder.nativeOrder());

	/**
	 * Whether this JVM's fastjson2 actually consumes the packed words the way
	 * {@link #packedWord0} produces them.
	 *
	 * <p>
	 * Verified once here rather than assumed, because the layout is an undocumented
	 * fastjson2 internal and a mismatch would corrupt <b>every field name in every
	 * message</b> silently. A fastjson2 upgrade that moves the layout, or a
	 * big-endian host (see the class javadoc), turns this off and the write paths
	 * fall back to {@code writeNameRaw}.
	 */
	public static final boolean PACKED_NAMES_SUPPORTED = selfCheck();

	private FieldNames() {
	}

	/**
	 * Round-trips a probe name of every supported length through both writer
	 * encodings and confirms the emitted text. Runs once per JVM at class init.
	 */
	private static boolean selfCheck() {
		try {
			for (int n = 2; n <= MAX_PACKED_LENGTH; n++) {
				StringBuilder sb = new StringBuilder(n);
				for (int i = 0; i < n; i++) {
					sb.append((char) ('a' + i % 26));
				}
				String name = sb.toString();
				String expected = "{\"" + name + "\":1}";
				for (int encoding = 0; encoding < 2; encoding++) {
					try (JSONWriter jw = encoding == 0 ? JSONWriter.ofUTF8() : JSONWriter.of()) {
						jw.startObject();
						writePackedName(jw, name);
						jw.writeInt32(1);
						jw.endObject();
						if (!expected.equals(jw.toString())) {
							return false;
						}
					}
				}
			}
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Writes a packed name by length. Used only by {@link #selfCheck()} — the
	 * generated encoders emit the matching {@code writeName<n>Raw} call directly,
	 * which is the whole point of the fast path (a length switch here measured
	 * slower than the arrays; see
	 * {@link io.suboptimal.buffjson.internal.typed.FieldName}).
	 */
	private static void writePackedName(JSONWriter jw, String name) {
		switch (name.length()) {
			case 2 -> jw.writeName2Raw(packedWord0(name));
			case 3 -> jw.writeName3Raw(packedWord0(name));
			case 4 -> jw.writeName4Raw(packedWord0(name));
			case 5 -> jw.writeName5Raw(packedWord0(name));
			case 6 -> jw.writeName6Raw(packedWord0(name));
			case 7 -> jw.writeName7Raw(packedWord0(name));
			case 8 -> jw.writeName8Raw(packedWord0(name));
			case 9 -> jw.writeName9Raw(packedWord0(name), packedTailInt(name));
			case 10 -> jw.writeName10Raw(packedWord0(name), packedWord1(name));
			case 11 -> jw.writeName11Raw(packedWord0(name), packedWord1(name));
			case 12 -> jw.writeName12Raw(packedWord0(name), packedWord1(name));
			case 13 -> jw.writeName13Raw(packedWord0(name), packedWord1(name));
			case 14 -> jw.writeName14Raw(packedWord0(name), packedWord1(name));
			case 15 -> jw.writeName15Raw(packedWord0(name), packedWord1(name));
			case 16 -> jw.writeName16Raw(packedWord0(name), packedWord1(name));
			default -> throw new IllegalArgumentException("not a packable length: " + name.length());
		}
	}

	/**
	 * Returns whether {@code jsonName} can use the packed {@code writeNameNRaw}
	 * fast path — 2–16 characters, printable ASCII, no JSON escaping needed.
	 */
	public static boolean isPackable(String jsonName) {
		int n = jsonName.length();
		if (n < 2 || n > MAX_PACKED_LENGTH) {
			return false;
		}
		return isRawWritable(jsonName);
	}

	/**
	 * Returns whether {@code jsonName} can be written as raw pre-encoded bytes or
	 * chars at all — printable ASCII with nothing needing JSON escaping, any
	 * length.
	 *
	 * <p>
	 * Protobuf field names always qualify, but an explicit
	 * {@code [json_name = "…"]} is an arbitrary string: it may carry non-ASCII
	 * (which {@link #nameWithColonBytes} cannot encode) or a
	 * quote/backslash/control character (which would break out of the JSON string).
	 * Those names must go through {@code JSONWriter.writeName(String)} +
	 * {@code writeColon()}, which escapes and transcodes properly.
	 */
	public static boolean isRawWritable(String jsonName) {
		for (int i = 0; i < jsonName.length(); i++) {
			char c = jsonName.charAt(i);
			if (c < 0x20 || c > 0x7e || c == '"' || c == '\\') {
				return false;
			}
		}
		return true;
	}

	/** The first packed word — the {@code long} argument of every variant. */
	public static long packedWord0(String jsonName) {
		checkPackable(jsonName);
		return (long) LONG_VIEW.get(quotedName(jsonName), wordOffset(jsonName.length()));
	}

	/**
	 * The second packed word — the trailing {@code long} of the 10–16 variants.
	 */
	public static long packedWord1(String jsonName) {
		checkPackable(jsonName);
		if (jsonName.length() < 10) {
			// Shorter names have no second word; the zero-padded read would silently
			// return 0 and write NUL bytes into the name.
			throw new IllegalArgumentException("no second packed word for a name of length " + jsonName.length());
		}
		return (long) LONG_VIEW.get(quotedName(jsonName), wordOffset(jsonName.length()) + 8);
	}

	/** The trailing {@code int} of the 9-character variant. */
	public static int packedTailInt(String jsonName) {
		checkPackable(jsonName);
		if (jsonName.length() != 9) {
			throw new IllegalArgumentException("the tail int is only for 9-character names, got " + jsonName.length());
		}
		return (int) INT_VIEW.get(quotedName(jsonName), wordOffset(jsonName.length()) + 8);
	}

	/**
	 * Pre-computes {@code "fieldName":} as a char array for the UTF-16
	 * {@link com.alibaba.fastjson2.JSONWriter#writeNameRaw(char[])} fallback. Only
	 * valid for {@linkplain #isRawWritable raw-writable} names.
	 */
	public static char[] nameWithColonChars(String jsonName) {
		char[] chars = new char[jsonName.length() + 3];
		chars[0] = '"';
		jsonName.getChars(0, jsonName.length(), chars, 1);
		chars[jsonName.length() + 1] = '"';
		chars[jsonName.length() + 2] = ':';
		return chars;
	}

	/**
	 * Pre-computes {@code "fieldName":} as a byte array for the UTF-8
	 * {@link com.alibaba.fastjson2.JSONWriter#writeNameRaw(byte[])} fallback. Only
	 * valid for {@linkplain #isRawWritable raw-writable} names — the byte cast
	 * below would truncate anything else.
	 */
	public static byte[] nameWithColonBytes(String jsonName) {
		byte[] bytes = new byte[jsonName.length() + 3];
		bytes[0] = '"';
		for (int i = 0; i < jsonName.length(); i++) {
			bytes[i + 1] = (byte) jsonName.charAt(i);
		}
		bytes[jsonName.length() + 1] = '"';
		bytes[jsonName.length() + 2] = ':';
		return bytes;
	}

	/**
	 * Escapes {@code jsonName} for embedding in generated Java source. Only needed
	 * for names that are not {@linkplain #isRawWritable raw-writable} — an explicit
	 * {@code json_name} carrying a quote, a backslash or a newline would otherwise
	 * emit source that does not compile.
	 *
	 * <p>
	 * A line terminator <b>must</b> come out as its {@code \n}/{@code \r} escape
	 * and never as a unicode escape: javac translates unicode escapes <i>before</i>
	 * tokenizing (JLS 3.3), so a unicode-escaped LF turns back into a real newline
	 * and leaves the string literal unclosed. Every other control character is safe
	 * as a unicode escape — only line terminators may not appear inside a literal.
	 */
	public static String javaStringLiteral(String jsonName) {
		StringBuilder sb = new StringBuilder(jsonName.length() + 8).append('"');
		for (int i = 0; i < jsonName.length(); i++) {
			char c = jsonName.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '\b' -> sb.append("\\b");
				case '\f' -> sb.append("\\f");
				default -> {
					if (c >= 0x20 && c <= 0x7e) {
						sb.append(c);
					} else {
						sb.append(String.format("\\u%04x", (int) c));
					}
				}
			}
		}
		return sb.append('"').toString();
	}

	/**
	 * {@code "name":} zero-padded to 32 bytes so the word reads at offsets up to 17
	 * stay in bounds.
	 */
	private static byte[] quotedName(String jsonName) {
		byte[] buf = new byte[32];
		buf[0] = '"';
		for (int i = 0; i < jsonName.length(); i++) {
			buf[i + 1] = (byte) jsonName.charAt(i);
		}
		buf[jsonName.length() + 1] = '"';
		buf[jsonName.length() + 2] = ':';
		return buf;
	}

	/**
	 * Offset into the quoted name at which the first packed word starts. Lengths 8
	 * and 16 are the two variants where fastjson2 emits the opening quote itself.
	 */
	private static int wordOffset(int length) {
		return (length == 8 || length == 16) ? 1 : 0;
	}

	private static void checkPackable(String jsonName) {
		if (!isPackable(jsonName)) {
			throw new IllegalArgumentException("Field name is not packable: " + jsonName);
		}
	}
}
