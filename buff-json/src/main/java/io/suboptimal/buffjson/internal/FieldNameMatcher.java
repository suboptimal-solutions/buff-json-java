package io.suboptimal.buffjson.internal;

import java.nio.charset.StandardCharsets;

import com.alibaba.fastjson2.JSONReader;

/**
 * Byte-exact matcher for one JSON object member name, built on fastjson2's
 * public {@code JSONReader.nextIfName4MatchN} family.
 *
 * <p>
 * The reader must be positioned on the opening quote of a member name. A caller
 * first compares {@link JSONReader#getRawInt()} (the opening quote and the
 * first three name bytes) against {@link #prefix()}; {@link #match} then
 * verifies the rest of {@code "name":}, including the closing quote and the
 * colon, and only on a full match consumes the name and any following
 * whitespace. On any mismatch nothing is consumed and the caller falls back to
 * {@link JSONReader#readFieldName()}, which handles every other spelling
 * (escapes, whitespace before the colon, unknown members). The fast route
 * therefore never changes which field a name resolves to; it only makes the
 * common spelling cheaper.
 *
 * <p>
 * Layout of the arguments, for a name of {@code L} bytes
 * ({@code 2 <= L <= 43}): the pattern {@code "name":} has {@code L + 3} bytes;
 * the first four are the prefix, and the remaining {@code L - 1} are passed as
 * little-endian 8-byte longs, then (if at least four bytes remain) one 4-byte
 * int, then (if exactly three bytes remain) one byte. The trailing {@code ":}
 * bytes not covered by those arguments are hard-coded by fastjson2. The layout
 * is pinned against the fastjson2 on the classpath by
 * {@code BuffJsonGeneratedNameMatchTest}.
 *
 * <p>
 * Names with fewer than two or more than 43 bytes, and names containing
 * anything other than printable ASCII (or a quote or backslash), have no
 * matcher.
 */
public final class FieldNameMatcher {

	/**
	 * Whether the fastjson2 on the classpath provides the {@code nextIfName4MatchN}
	 * family with the argument layout implemented here. Established once, by
	 * running the matcher for representative name lengths against the byte and
	 * UTF-16 readers; when it is {@code false} (an older fastjson2, or one whose
	 * semantics changed) generated decoders and runtime schemas skip the fast route
	 * and resolve names through {@link JSONReader#readFieldName()} as before.
	 */
	public static final boolean AVAILABLE = selfCheck();

	private static final int MIN_LENGTH = 2;
	private static final int MAX_LENGTH = 43;

	private final int length;
	private final int prefix;
	private final long l0, l1, l2, l3, l4;
	private final int i0;
	private final byte b0;

	private FieldNameMatcher(int length, int prefix, long[] longs, int i0, byte b0) {
		this.length = length;
		this.prefix = prefix;
		this.l0 = longs[0];
		this.l1 = longs[1];
		this.l2 = longs[2];
		this.l3 = longs[3];
		this.l4 = longs[4];
		this.i0 = i0;
		this.b0 = b0;
	}

	private static boolean selfCheck() {
		try {
			// One name per layout shape: bare colon (2), int (5), int+byte (8), long (9),
			// long+int (13),
			// long+int+byte (16), two longs (17), and the maximum (43).
			int[] lengths = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16, 17, 24, 25, 32, 33, 40, 41, 42, 43};
			for (int length : lengths) {
				String name = "n"
						+ "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(2).substring(0, length - 1);
				FieldNameMatcher matcher = create(name);
				String json = "{\"" + name + "\": 7,\"z\":1}";
				String wrong = "{\"" + name.substring(0, name.length() - 1) + "X\":7}";
				for (int kind = 0; kind < 3; kind++) {
					if (!selfCheckOne(matcher, json, wrong, kind)) {
						return false;
					}
				}
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	private static boolean selfCheckOne(FieldNameMatcher matcher, String json, String wrong, int kind) {
		try (JSONReader r = open(json, kind); JSONReader w = open(wrong, kind)) {
			r.nextIfObjectStart();
			w.nextIfObjectStart();
			int offset = w.getOffset();
			boolean rejected = !(w.getRawInt() == matcher.prefix && matcher.match(w)) && w.getOffset() == offset
					&& w.current() == '"';
			return rejected && r.getRawInt() == matcher.prefix && matcher.match(r) && r.readInt32Value() == 7
					&& r.readFieldName().equals("z") && r.readInt32Value() == 1 && r.nextIfObjectEnd();
		}
	}

	private static JSONReader open(String json, int kind) {
		return switch (kind) {
			case 0 -> JSONReader.of(json.getBytes(StandardCharsets.UTF_8));
			case 1 -> JSONReader.of(json);
			default -> JSONReader.of(json.toCharArray());
		};
	}

	/**
	 * Returns the matcher for {@code jsonName}, or {@code null} if it has none (or
	 * the fastjson2 in use is not known to support them).
	 */
	public static FieldNameMatcher of(String jsonName) {
		return AVAILABLE ? create(jsonName) : null;
	}

	private static FieldNameMatcher create(String jsonName) {
		int length = jsonName.length();
		if (length < MIN_LENGTH || length > MAX_LENGTH) {
			return null;
		}
		for (int i = 0; i < length; i++) {
			char c = jsonName.charAt(i);
			if (c < 0x20 || c > 0x7e || c == '"' || c == '\\') {
				return null;
			}
		}
		byte[] p = ("\"" + jsonName + "\":").getBytes(StandardCharsets.US_ASCII);
		int prefix = (p[0] & 0xff) | (p[1] & 0xff) << 8 | (p[2] & 0xff) << 16 | (p[3] & 0xff) << 24;
		long[] longs = new long[5];
		int o = 4;
		int n = 0;
		while (p.length - o >= 8) {
			long v = 0;
			for (int i = 7; i >= 0; i--) {
				v = v << 8 | (p[o + i] & 0xffL);
			}
			longs[n++] = v;
			o += 8;
		}
		int i0 = 0;
		if (p.length - o >= 4) {
			for (int i = 3; i >= 0; i--) {
				i0 = i0 << 8 | (p[o + i] & 0xff);
			}
			o += 4;
		}
		byte b0 = p.length - o == 3 ? p[o] : 0;
		return new FieldNameMatcher(length, prefix, longs, i0, b0);
	}

	/**
	 * The first four bytes of {@code "name":} as read by
	 * {@code JSONReader.getRawInt()}.
	 */
	public int prefix() {
		return prefix;
	}

	/**
	 * Java source of the call {@code <reader>.nextIfName4Match<L>(...)} that
	 * {@link #match} performs, with the constants inlined as literals. Used by the
	 * protoc plugin so generated decoders embed exactly the arguments this class
	 * passes at runtime -- there is one implementation of the argument layout.
	 */
	public String javaCall(String readerVariable) {
		StringBuilder args = new StringBuilder();
		int tail = length - 1;
		int longs = tail / 8;
		int rest = tail % 8;
		long[] values = {l0, l1, l2, l3, l4};
		for (int i = 0; i < longs; i++) {
			args.append(args.length() == 0 ? "" : ", ").append(String.format("0x%016xL", values[i]));
		}
		if (rest >= 4) {
			args.append(args.length() == 0 ? "" : ", ").append(String.format("0x%08x", i0));
			rest -= 4;
		}
		if (rest == 3) {
			args.append(args.length() == 0 ? "" : ", ").append(String.format("(byte) 0x%02x", b0 & 0xff));
		}
		return readerVariable + ".nextIfName4Match" + length + "(" + args + ")";
	}

	/**
	 * Consumes {@code "name":} and following whitespace iff the input matches
	 * exactly.
	 *
	 * <p>
	 * Precondition: {@code reader.getRawInt() == prefix()}. fastjson2 splits the
	 * check in two: the caller verifies the opening quote and the first three name
	 * bytes (which is also how it picks a candidate), this method verifies
	 * everything after them. Calling it without the prefix check can accept a
	 * different name.
	 */
	public boolean match(JSONReader reader) {
		return switch (length) {
			case 2 -> reader.nextIfName4Match2();
			case 3 -> reader.nextIfName4Match3();
			case 4 -> reader.nextIfName4Match4(b0);
			case 5 -> reader.nextIfName4Match5(i0);
			case 6 -> reader.nextIfName4Match6(i0);
			case 7 -> reader.nextIfName4Match7(i0);
			case 8 -> reader.nextIfName4Match8(i0, b0);
			case 9 -> reader.nextIfName4Match9(l0);
			case 10 -> reader.nextIfName4Match10(l0);
			case 11 -> reader.nextIfName4Match11(l0);
			case 12 -> reader.nextIfName4Match12(l0, b0);
			case 13 -> reader.nextIfName4Match13(l0, i0);
			case 14 -> reader.nextIfName4Match14(l0, i0);
			case 15 -> reader.nextIfName4Match15(l0, i0);
			case 16 -> reader.nextIfName4Match16(l0, i0, b0);
			case 17 -> reader.nextIfName4Match17(l0, l1);
			case 18 -> reader.nextIfName4Match18(l0, l1);
			case 19 -> reader.nextIfName4Match19(l0, l1);
			case 20 -> reader.nextIfName4Match20(l0, l1, b0);
			case 21 -> reader.nextIfName4Match21(l0, l1, i0);
			case 22 -> reader.nextIfName4Match22(l0, l1, i0);
			case 23 -> reader.nextIfName4Match23(l0, l1, i0);
			case 24 -> reader.nextIfName4Match24(l0, l1, i0, b0);
			case 25 -> reader.nextIfName4Match25(l0, l1, l2);
			case 26 -> reader.nextIfName4Match26(l0, l1, l2);
			case 27 -> reader.nextIfName4Match27(l0, l1, l2);
			case 28 -> reader.nextIfName4Match28(l0, l1, l2, b0);
			case 29 -> reader.nextIfName4Match29(l0, l1, l2, i0);
			case 30 -> reader.nextIfName4Match30(l0, l1, l2, i0);
			case 31 -> reader.nextIfName4Match31(l0, l1, l2, i0);
			case 32 -> reader.nextIfName4Match32(l0, l1, l2, i0, b0);
			case 33 -> reader.nextIfName4Match33(l0, l1, l2, l3);
			case 34 -> reader.nextIfName4Match34(l0, l1, l2, l3);
			case 35 -> reader.nextIfName4Match35(l0, l1, l2, l3);
			case 36 -> reader.nextIfName4Match36(l0, l1, l2, l3, b0);
			case 37 -> reader.nextIfName4Match37(l0, l1, l2, l3, i0);
			case 38 -> reader.nextIfName4Match38(l0, l1, l2, l3, i0);
			case 39 -> reader.nextIfName4Match39(l0, l1, l2, l3, i0);
			case 40 -> reader.nextIfName4Match40(l0, l1, l2, l3, i0, b0);
			case 41 -> reader.nextIfName4Match41(l0, l1, l2, l3, l4);
			case 42 -> reader.nextIfName4Match42(l0, l1, l2, l3, l4);
			case 43 -> reader.nextIfName4Match43(l0, l1, l2, l3, l4);
			default -> false;
		};
	}
}
