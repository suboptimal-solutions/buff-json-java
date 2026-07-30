package io.suboptimal.buffjson.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

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
 * opening quote separately and stores only the name body. The words are read in
 * {@linkplain ByteOrder#nativeOrder() native byte order} because fastjson2
 * stores them with {@code Unsafe.putLong}, so the same constants are correct on
 * little- and big-endian hosts alike.
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

	private FieldNames() {
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
		return (long) LONG_VIEW.get(quotedName(jsonName), wordOffset(jsonName.length()) + 8);
	}

	/** The trailing {@code int} of the 9-character variant. */
	public static int packedTailInt(String jsonName) {
		checkPackable(jsonName);
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
	 */
	public static String javaStringLiteral(String jsonName) {
		StringBuilder sb = new StringBuilder(jsonName.length() + 8).append('"');
		for (int i = 0; i < jsonName.length(); i++) {
			char c = jsonName.charAt(i);
			if (c == '"' || c == '\\') {
				sb.append('\\').append(c);
			} else if (c >= 0x20 && c <= 0x7e) {
				sb.append(c);
			} else {
				sb.append(String.format("\\u%04x", (int) c));
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
