package io.suboptimal.buffjson.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UnsafeByteOperations;

/**
 * A forward-only cursor over UTF-8 JSON bytes that reads <em>canonical</em>
 * proto3 JSON directly, without going through a fastjson2 {@code JSONReader}.
 *
 * <p>
 * It exists for the generated {@code readFast} decoders and follows one rule:
 * <b>accept only input whose meaning is unambiguous, and throw {@link #bail()}
 * for everything else</b>. Callers catch the bail-out and re-run the whole
 * decode through the general decoder, so behaviour for non-canonical or invalid
 * input -- including its exception types and messages -- is exactly that of the
 * general decoder. Nothing here ever reports a parse error of its own.
 *
 * <p>
 * What is accepted:
 *
 * <ul>
 * <li>JSON structure with insignificant whitespace ({@code space, \t, \n, \r})
 * <li>integers as {@code -?(0|[1-9][0-9]*)} (int64/uint64 optionally quoted);
 * no leading zeros, plus signs, fractions or exponents
 * <li>float/double as a JSON number, or the quoted {@code "NaN"},
 * {@code "Infinity"}, {@code "-Infinity"}; results are correctly rounded
 * <li>strings with the standard escapes and well-formed UTF-8; anything else
 * (raw control characters, malformed UTF-8, bad escapes) bails
 * <li>bytes as a quoted base64 string decoded by {@link Base64#getDecoder()}
 * <li>canonical RFC 3339 UTC {@code Timestamp} and {@code Duration} strings
 * </ul>
 *
 * <p>
 * The cursor invariant: after construction and after every cursor operation,
 * the position is on the next non-whitespace byte (or at the end).
 *
 * <p>
 * Generated {@code readFast} methods use this class in two ways. The hot tokens
 * (whitespace, plain strings, 32-bit integers, member names) are read by
 * public-static helpers on the document array with a position kept in a local
 * variable of the generated method -- the cheapest form, because nothing is
 * loaded from or stored to the cursor object between tokens. Everything rarer
 * (64-bit integers, floating point, bytes, Timestamp/Duration, escaped strings,
 * skipping unknown members) is done by the cursor's own methods, with the
 * position handed over through {@link #pos(int)} and read back with
 * {@link #pos()}.
 *
 * <p>
 * Not thread-safe; create one per decode call. This class is public only
 * because generated code lives in the application's packages -- it is not API.
 */
public final class FastInput {

	/** Stackless bail-out signal: "not canonical, use the general decoder". */
	public static final class Bail extends RuntimeException {
		private static final long serialVersionUID = 1L;

		private Bail() {
			super("not canonical", null, false, false);
		}
	}

	private static final Bail BAIL = new Bail();

	private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
	private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
	private static final Base64.Decoder BASE64 = Base64.getDecoder();

	private static final int MAX_SKIP_DEPTH = 64;
	private static final double[] POW10 = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13,
			1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};
	private static final float[] POW10F = {1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f};

	/**
	 * Deepest object nesting read here; a deeper document is left to the general
	 * decoder, so recursion in generated {@code readFast} methods stays bounded no
	 * matter what a client sends.
	 */
	static final int MAX_DEPTH = 100;

	private final byte[] b;
	private final int end;
	private final boolean latin1;
	private boolean runtimeCodegen;
	private int p;
	private int depth;

	/** A cursor over UTF-8 bytes. */
	public FastInput(byte[] bytes, int offset, int length) {
		this(bytes, offset, length, false);
	}

	/**
	 * A cursor over {@code bytes}; with {@code latin1} the bytes are the backing
	 * array of a Latin-1 {@code String}, so a byte of 0x80 or more is a character
	 * of its own and is <em>never</em> decoded as part of a UTF-8 sequence (that
	 * would turn {@code "Ã©"} into {@code "é"}).
	 */
	public FastInput(byte[] bytes, int offset, int length, boolean latin1) {
		if (offset < 0 || length < 0 || offset > bytes.length - length) {
			throw new IndexOutOfBoundsException();
		}
		this.b = bytes;
		this.p = offset;
		this.end = offset + length;
		this.latin1 = latin1;
		skipWs();
	}

	/**
	 * Whether a reader met while reading may be generated at run time for a nested
	 * message that has none (see {@code RuntimeReaders}); set by the decoder that
	 * created the cursor from its own setting.
	 */
	public FastInput allowRuntimeCodegen(boolean allowed) {
		this.runtimeCodegen = allowed;
		return this;
	}

	public boolean runtimeCodegen() {
		return runtimeCodegen;
	}

	/** The bail-out to throw for anything this cursor does not handle. */
	public static RuntimeException bail() {
		return BAIL;
	}

	// ---------------------------------------------------------- straight-line
	// support
	//
	// Generated readFast methods keep the position in a local variable and index
	// the document array directly, calling the static helpers below (which take a
	// position and return the next one) for the common tokens; the cursor's own
	// methods serve the rarer ones, with the position handed over through
	// pos()/pos(int). A read past the end of the array is an
	// IndexOutOfBoundsException, which callers treat exactly like a Bail; for a
	// slice of a larger array the readers may look past the slice, and finished()
	// rejects a document that did.

	/**
	 * The document array. Straight-line readers index it directly and may therefore
	 * look at bytes past {@code end} when the cursor covers a slice;
	 * {@link #finished()} is false for a document that needed them.
	 */
	public byte[] array() {
		return b;
	}

	/** Current position: the next byte that is not whitespace, or the end. */
	public int pos() {
		return p;
	}

	/** Continues at {@code position}, which must be at a non-whitespace byte. */
	public void pos(int position) {
		p = position;
	}

	/** Entering an object of the document; bails past {@link #MAX_DEPTH}. */
	public void enter() {
		if (depth >= MAX_DEPTH) {
			throw BAIL;
		}
		depth++;
	}

	public void leave() {
		depth--;
	}

	public static int i4(byte[] b, int at) {
		return (int) INT.get(b, at);
	}

	public static long l8(byte[] b, int at) {
		return (long) LONG.get(b, at);
	}

	/**
	 * The first position at or after {@code p} that is not insignificant
	 * whitespace.
	 */
	public static int ws(byte[] b, int p) {
		final int n = b.length;
		// the commonest case first: one blank after a colon or comma
		if (p + 1 < n && b[p] == ' ' && b[p + 1] > ' ') {
			return p + 1;
		}
		while (p < n) {
			byte c = b[p];
			if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
				break;
			}
			p++;
		}
		return p;
	}

	/**
	 * Index of the closing quote of the string whose content starts at {@code s},
	 * if that content is printable ASCII without a backslash; -1 if a backslash,
	 * control character or non-ASCII byte comes first (or the string does not end).
	 * One word-at-a-time pass locates the first byte that is not plain, so a short
	 * string costs a single iteration.
	 */
	public static int plainStringEnd(byte[] b, int s) {
		int i = s;
		final int n = b.length;
		while (i + 8 <= n) {
			long w = (long) LONG.get(b, i);
			long quote = w ^ 0x2222222222222222L;
			long slash = w ^ 0x5C5C5C5C5C5C5C5CL;
			long special = (((quote - 0x0101010101010101L) & ~quote) | ((slash - 0x0101010101010101L) & ~slash)
					| ((w - 0x2020202020202020L) & ~w) | w) & 0x8080808080808080L;
			if (special != 0) {
				// the lowest flagged byte is the first quote, backslash, control or non-ASCII
				int at = i + (Long.numberOfTrailingZeros(special) >>> 3);
				return b[at] == '"' ? at : -1;
			}
			i += 8;
		}
		for (; i < n; i++) {
			byte c = b[i];
			if (c == '"') {
				return i;
			}
			if (c == '\\' || c < 0x20) { // also true for bytes >= 0x80 (negative)
				return -1;
			}
		}
		return -1;
	}

	/**
	 * A canonical int32 at {@code p}: {@code -?(0|[1-9][0-9]*)} within range. The
	 * value is the high half of the result, the position after the digits the low
	 * half. Whether the number ends there (rather than continuing as {@code 1.5} or
	 * {@code 12x}) is left to the caller's check of what follows.
	 */
	public static long int32At(byte[] b, int p) {
		final int n = b.length;
		int i = p;
		boolean negative = false;
		if (i < n && b[i] == '-') {
			negative = true;
			i++;
		}
		final int start = i;
		long r = digitsUpTo11(b, start, n);
		int digits = (int) (r & 15);
		if (digits == 0 || digits > 10 || (digits > 1 && b[start] == '0')) {
			throw BAIL;
		}
		long v = r >>> 4;
		if (negative) {
			v = -v;
		}
		if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
			throw BAIL;
		}
		return (v << 32) | (start + digits);
	}

	/**
	 * Like {@link #int32At} for uint32 / fixed32; the value's bits are the high
	 * half.
	 */
	public static long uint32At(byte[] b, int p) {
		final int n = b.length;
		long r = digitsUpTo11(b, p, n);
		int digits = (int) (r & 15);
		long v = r >>> 4;
		if (digits == 0 || digits > 10 || (digits > 1 && b[p] == '0') || v > 0xFFFF_FFFFL) {
			throw BAIL;
		}
		return (((long) (int) v) << 32) | (p + digits);
	}

	/**
	 * The ASCII digits at {@code start}, at most 11 of them (one more than an int32
	 * or uint32 can have, so the caller can tell "too long"): {@code value << 4 |
	 * count}. Eight digits are recognised and converted with a few word operations
	 * and no data-dependent branch, which is what makes typical numbers cheap
	 * whatever their length.
	 */
	private static long digitsUpTo11(byte[] b, int start, int n) {
		long v = 0;
		int count = 0;
		if (start + 8 <= n) {
			long w = (long) LONG.get(b, start);
			count = digitRun8(w);
			if (count > 0) {
				v = digitValue8(w, count);
			}
			if (count < 8) {
				return (v << 4) | count;
			}
		}
		int i = start + count;
		int d;
		while (count < 11 && i < n && (d = b[i] - '0') >= 0 && d <= 9) {
			v = v * 10 + d;
			i++;
			count++;
		}
		return (v << 4) | count;
	}

	/**
	 * How many of the first eight bytes of {@code w} (little-endian, first byte
	 * lowest) are ASCII digits before the first one that is not. A byte is flagged
	 * when it is above '9' (adding 0x46 sets its top bit) or below '0' (subtracting
	 * 0x30 borrows into its top bit); carries only travel upwards, so the lowest
	 * flagged byte is exact.
	 */
	private static int digitRun8(long w) {
		long notDigit = ((w + 0x4646464646464646L) | (w - 0x3030303030303030L)) & 0x8080808080808080L;
		return notDigit == 0 ? 8 : Long.numberOfTrailingZeros(notDigit) >>> 3;
	}

	/**
	 * The value of the first {@code len} (1..8) bytes of {@code w}, all of which
	 * are digits: the digits are moved to the top of the word, the vacated low
	 * bytes (leading zeros) are cleared, and pairs, quads and the whole word are
	 * combined with three multiplications.
	 */
	private static long digitValue8(long w, int len) {
		long d = (w << ((8 - len) << 3)) & 0x0F0F0F0F0F0F0F0FL;
		d = d * 10 + (d >>> 8);
		return (((d & 0x000000FF000000FFL) * 0x000F424000000064L)
				+ (((d >>> 16) & 0x000000FF000000FFL) * 0x0000271000000001L)) >>> 32;
	}

	/**
	 * An int32 map key: the number of {@link #int32At} between quotes; the position
	 * is after the closing quote.
	 */
	public static long int32KeyAt(byte[] b, int p) {
		if (b[p] != '"') {
			throw BAIL;
		}
		long r = int32At(b, p + 1);
		int i = (int) r;
		if (b[i] != '"') {
			throw BAIL;
		}
		return (r & 0xFFFF_FFFF_0000_0000L) | (i + 1);
	}

	/** A uint32 / fixed32 map key. */
	public static long uint32KeyAt(byte[] b, int p) {
		if (b[p] != '"') {
			throw BAIL;
		}
		long r = uint32At(b, p + 1);
		int i = (int) r;
		if (b[i] != '"') {
			throw BAIL;
		}
		return (r & 0xFFFF_FFFF_0000_0000L) | (i + 1);
	}

	/**
	 * Finishes a number the static helpers read: it must end at a token boundary
	 * inside the input.
	 */
	private void tokenEnd(int next) {
		if (next > end || !delimiterAt(next)) {
			throw BAIL;
		}
		p = next;
		skipWs();
	}

	// ------------------------------------------------------------------ structure

	private void skipWs() {
		int i = p;
		final byte[] b = this.b;
		final int end = this.end;
		while (i < end) {
			byte c = b[i];
			if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
				break;
			}
			i++;
		}
		p = i;
	}

	/** True once only whitespace was left, i.e. the whole document was consumed. */
	public boolean atEnd() {
		return p >= end;
	}

	/**
	 * True if nothing but insignificant whitespace follows the position: the
	 * document a generated {@code readFast} read is complete. (That method leaves
	 * the position right after the closing brace, without skipping whitespace.)
	 */
	public boolean finished() {
		int i = p;
		if (i > end) {
			return false; // a straight-line reader ran past the end of a slice
		}
		while (i < end) {
			byte c = b[i];
			if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
				return false;
			}
			i++;
		}
		return true;
	}

	public void objectStart() {
		if (p >= end || b[p] != '{' || depth >= MAX_DEPTH) {
			throw BAIL;
		}
		depth++;
		p++;
		skipWs();
	}

	/** Consumes {@code '}'} if it is next; false (consuming nothing) otherwise. */
	public boolean objectEnd() {
		if (p < end && b[p] == '}') {
			depth--;
			p++;
			skipWs();
			return true;
		}
		return false;
	}

	/** Consumes the {@code '}'} closing a non-empty object. */
	public void objectEndRequired() {
		if (!objectEnd()) {
			throw BAIL;
		}
	}

	public void arrayStart() {
		if (p >= end || b[p] != '[') {
			throw BAIL;
		}
		p++;
		skipWs();
	}

	public boolean arrayEnd() {
		if (p < end && b[p] == ']') {
			p++;
			skipWs();
			return true;
		}
		return false;
	}

	public void arrayEndRequired() {
		if (!arrayEnd()) {
			throw BAIL;
		}
	}

	/** Consumes a {@code ','} separator if it is next. */
	public boolean comma() {
		if (p < end && b[p] == ',') {
			p++;
			skipWs();
			return true;
		}
		return false;
	}

	/** Consumes the {@code ':'} after a member name that was read as a string. */
	public void colon() {
		if (p >= end || b[p] != ':') {
			throw BAIL;
		}
		p++;
		skipWs();
	}

	/** Consumes a JSON {@code null} literal if it is next. */
	public boolean nullLiteral() {
		int i = p;
		if (i + 4 <= end && b[i] == 'n' && b[i + 1] == 'u' && b[i + 2] == 'l' && b[i + 3] == 'l'
				&& delimiterAt(i + 4)) {
			p = i + 4;
			skipWs();
			return true;
		}
		return false;
	}

	/** True at a byte that ends a number or literal token. */
	private boolean delimiterAt(int i) {
		if (i >= end) {
			return true;
		}
		byte c = b[i];
		return c == ',' || c == '}' || c == ']' || c == ' ' || c == '\n' || c == '\r' || c == '\t';
	}

	// ------------------------------------------------------------ member names

	/**
	 * Reads a member name in the general spelling (quoted, ASCII, no escapes, any
	 * whitespace before the colon) and the following colon.
	 */
	public String nameString() {
		String name = plainString();
		if (p >= end || b[p] != ':') {
			throw BAIL;
		}
		p++;
		skipWs();
		return name;
	}

	// ------------------------------------------------------------------ scalars

	public boolean bool() {
		int i = p;
		if (i + 4 <= end && b[i] == 't' && b[i + 1] == 'r' && b[i + 2] == 'u' && b[i + 3] == 'e'
				&& delimiterAt(i + 4)) {
			p = i + 4;
			skipWs();
			return true;
		}
		if (i + 5 <= end && b[i] == 'f' && b[i + 1] == 'a' && b[i + 2] == 'l' && b[i + 3] == 's' && b[i + 4] == 'e'
				&& delimiterAt(i + 5)) {
			p = i + 5;
			skipWs();
			return false;
		}
		throw BAIL;
	}

	public int int32() {
		long r = int32At(b, p);
		tokenEnd((int) r);
		return (int) (r >> 32);
	}

	/** uint32 / fixed32 as the signed int with the same bits. */
	public int uint32() {
		long r = uint32At(b, p);
		tokenEnd((int) r);
		return (int) (r >> 32);
	}

	/** int64 family: a quoted or bare canonical integer. */
	public long int64() {
		int i = p;
		boolean quoted = i < end && b[i] == '"';
		return integer(quoted);
	}

	/** uint64 / fixed64 as the signed long with the same bits. */
	public long uint64() {
		return unsigned64(false);
	}

	private long unsigned64(boolean key) {
		int i = p;
		boolean quoted = i < end && b[i] == '"';
		if (key && !quoted) {
			throw BAIL; // a JSON object key is always a string
		}
		i += quoted ? 1 : 0;
		final byte[] b = this.b;
		final int start = i;
		i = digitRun19(start);
		int digits = i - start;
		if (digits == 0 || (digits > 1 && b[start] == '0')) {
			throw BAIL;
		}
		long v = acc; // exact below 10^19 even where it does not fit a signed long
		if (i < end && b[i] >= '0' && b[i] <= '9') {
			// a 20th digit: 2^64 - 1 has 20 digits, so v * 10 + d must stay below 2^64
			int d = b[i] - '0';
			if (Long.compareUnsigned(v, 1844674407370955161L) > 0 || (v == 1844674407370955161L && d > 5)) {
				throw BAIL;
			}
			v = v * 10 + d;
			i++;
			if (i < end && b[i] >= '0' && b[i] <= '9') {
				throw BAIL;
			}
		}
		if (quoted) {
			if (i >= end || b[i] != '"') {
				throw BAIL;
			}
			i++;
		} else if (!delimiterAt(i)) {
			throw BAIL;
		}
		p = i;
		skipWs();
		return v;
	}

	/**
	 * Canonical signed integer with at most {@code maxDigits} digits. Everything
	 * beyond {@code -?(0|[1-9][0-9]*)} -- including a fraction, an exponent or a
	 * leading zero -- bails, so nothing the general decoder would round or reject
	 * is ever accepted here.
	 */
	private long integer(boolean quoted) {
		final byte[] b = this.b;
		int i = p;
		if (quoted) {
			i++;
		}
		boolean negative = false;
		if (i < end && b[i] == '-') {
			negative = true;
			i++;
		}
		final int start = i;
		i = digitRun19(start);
		int digits = i - start;
		if (digits == 0 || (digits > 1 && b[start] == '0')) {
			throw BAIL;
		}
		if (i < end && b[i] >= '0' && b[i] <= '9') {
			throw BAIL; // more than 19 digits
		}
		long v = acc;
		if (digits == 19 && v < 0) {
			// 19 digits overflowed a signed long (only -2^63 exactly is representable)
			if (negative && v == Long.MIN_VALUE) {
				v = Long.MIN_VALUE;
				negative = false;
			} else {
				throw BAIL;
			}
		}
		if (quoted) {
			// the closing quote ends the token; whatever follows (a colon after a map key,
			// a comma, a brace) is checked by the structural call that reads it
			if (i >= end || b[i] != '"') {
				throw BAIL;
			}
			i++;
		} else if (!delimiterAt(i)) {
			throw BAIL;
		}
		p = i;
		skipWs();
		return negative ? -v : v;
	}

	/** Scratch result of {@link #digitRun19}. */
	private long acc;

	private static final long[] POW10_LONG = {1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L,
			100_000_000L};

	/**
	 * The ASCII digits at {@code start}, at most 19 of them: leaves their value in
	 * {@link #acc} (modulo 2^64, which is exact below 10^19) and returns the index
	 * after them. Eight digits at a time, like {@link #digitsUpTo11}.
	 */
	private int digitRun19(int start) {
		final byte[] b = this.b;
		final int limit = Math.min(end, start + 19);
		int i = start;
		long v = 0;
		while (i + 8 <= limit) {
			long w = (long) LONG.get(b, i);
			int len = digitRun8(w);
			if (len > 0) {
				v = v * POW10_LONG[len] + digitValue8(w, len);
			}
			i += len;
			if (len < 8) {
				acc = v;
				return i;
			}
		}
		int d;
		while (i < limit && (d = b[i] - '0') >= 0 && d <= 9) {
			v = v * 10 + d;
			i++;
		}
		acc = v;
		return i;
	}

	// -------------------------------------------------------------- floating point

	public double float64() {
		if (p < end && b[p] == '"') {
			return specialDouble();
		}
		return number(false);
	}

	public float float32() {
		if (p < end && b[p] == '"') {
			return (float) specialDouble();
		}
		return (float) number(true);
	}

	private double specialDouble() {
		int i = p;
		double v;
		if (matches(i, "\"NaN\"")) {
			v = Double.NaN;
			i += 5;
		} else if (matches(i, "\"Infinity\"")) {
			v = Double.POSITIVE_INFINITY;
			i += 10;
		} else if (matches(i, "\"-Infinity\"")) {
			v = Double.NEGATIVE_INFINITY;
			i += 11;
		} else {
			throw BAIL;
		}
		if (!delimiterAt(i)) {
			throw BAIL;
		}
		p = i;
		skipWs();
		return v;
	}

	private boolean matches(int at, String literal) {
		int n = literal.length();
		if (at + n > end) {
			return false;
		}
		for (int k = 0; k < n; k++) {
			if (b[at + k] != literal.charAt(k)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Parses a JSON number into a double, or (for {@code asFloat}) into a float
	 * carried in a double. Short numbers use one IEEE operation on exact operands
	 * (Clinger), other numbers of up to 19 significant digits the Eisel-Lemire
	 * algorithm ({@link FastDouble}); both are correctly rounded, i.e. bit-for-bit
	 * what the JDK parser returns. Numbers with more digits go to the JDK parser.
	 * An infinite result bails so the general decoder keeps deciding what an
	 * out-of-range value means.
	 */
	private double number(boolean asFloat) {
		final byte[] b = this.b;
		int i = p;
		final int tokenStart = i;
		boolean negative = false;
		if (i < end && b[i] == '-') {
			negative = true;
			i++;
		}
		long mantissa = 0;
		int digits = 0; // digits accumulated into the mantissa
		boolean exact = true; // false once a digit did not fit in the mantissa
		int exponent = 0;
		int intStart = i;
		while (i < end) {
			int d = b[i] - '0';
			if (d < 0 || d > 9) {
				break;
			}
			if (digits < 19) {
				mantissa = mantissa * 10 + d;
				if (mantissa != 0) {
					digits++;
				}
			} else {
				exact = false;
				exponent++;
			}
			i++;
		}
		int intDigits = i - intStart;
		if (intDigits == 0 || (intDigits > 1 && b[intStart] == '0')) {
			throw BAIL;
		}
		if (i < end && b[i] == '.') {
			i++;
			int fracStart = i;
			while (i < end) {
				int d = b[i] - '0';
				if (d < 0 || d > 9) {
					break;
				}
				if (digits < 19) {
					mantissa = mantissa * 10 + d;
					if (mantissa != 0) {
						digits++;
					}
					exponent--;
				} else {
					exact = false;
				}
				i++;
			}
			if (i == fracStart) {
				throw BAIL;
			}
		}
		if (i < end && (b[i] == 'e' || b[i] == 'E')) {
			i++;
			boolean expNegative = false;
			if (i < end && (b[i] == '+' || b[i] == '-')) {
				expNegative = b[i] == '-';
				i++;
			}
			int expStart = i;
			int e = 0;
			while (i < end) {
				int d = b[i] - '0';
				if (d < 0 || d > 9) {
					break;
				}
				e = e * 10 + d;
				if (e > 100_000) {
					throw BAIL;
				}
				i++;
			}
			if (i == expStart) {
				throw BAIL;
			}
			exponent += expNegative ? -e : e;
		}
		if (!delimiterAt(i)) {
			throw BAIL;
		}
		double result;
		if (mantissa == 0) {
			result = negative ? -0.0 : 0.0;
		} else if (!exact) {
			// more than 19 significant digits: only the JDK parser decides those
			result = parseWithJdk(tokenStart, i, asFloat);
		} else if (!asFloat) {
			if (mantissa > 0 && mantissa <= (1L << 53) && exponent >= -22 && exponent <= 22) {
				// both operands exact, one IEEE operation: correctly rounded (Clinger)
				double m = (double) mantissa;
				result = exponent < 0 ? m / POW10[-exponent] : m * POW10[exponent];
			} else {
				result = FastDouble.toDouble(mantissa, exponent);
			}
			result = negative ? -result : result;
		} else {
			if (mantissa > 0 && mantissa <= (1L << 24) && exponent >= -10 && exponent <= 10) {
				float m = (float) mantissa;
				float f = exponent < 0 ? m / POW10F[-exponent] : m * POW10F[exponent];
				result = negative ? -f : f;
			} else {
				result = floatFromDecimal(mantissa, exponent, negative, tokenStart, i);
			}
		}
		if (Double.isInfinite(result)) {
			throw BAIL;
		}
		p = i;
		skipWs();
		return result;
	}

	private double parseWithJdk(int from, int to, boolean asFloat) {
		String token = new String(b, from, to - from, StandardCharsets.ISO_8859_1);
		return asFloat ? Float.parseFloat(token) : Double.parseDouble(token);
	}

	/**
	 * The float nearest to {@code mantissa * 10^exponent}. Rounding the correctly
	 * rounded <em>double</em> to float is wrong only when that double sits exactly
	 * halfway between two floats (the decimal may lie on either side of the halfway
	 * point); those, and the float subnormal and overflow ranges, go to the JDK
	 * parser.
	 */
	private double floatFromDecimal(long mantissa, int exponent, boolean negative, int from, int to) {
		double d = FastDouble.toDouble(mantissa, exponent);
		if (d >= 0x1p-126 && d < 0x1p127) {
			long bits = Double.doubleToRawLongBits(d);
			if ((bits & 0x1FFFFFFFL) != 0x10000000L) {
				float f = (float) d;
				return negative ? -f : f;
			}
		}
		return parseWithJdk(from, to, true);
	}

	// --------------------------------------------------------------------- strings

	/**
	 * A JSON string value. Plain ASCII takes a word-at-a-time scan; escapes and
	 * multi-byte UTF-8 are decoded strictly.
	 */
	public String string() {
		final int i = p;
		if (i >= end || b[i] != '"') {
			throw BAIL;
		}
		int s = i + 1;
		int e = scanPlain(s);
		if (e < 0) {
			return stringSlow(s);
		}
		String v = new String(b, s, e - s, StandardCharsets.ISO_8859_1);
		p = e + 1;
		skipWs();
		return v;
	}

	/**
	 * A string that must be plain ASCII without escapes (member names, map keys,
	 * base64).
	 */
	private String plainString() {
		final int i = p;
		if (i >= end || b[i] != '"') {
			throw BAIL;
		}
		int s = i + 1;
		int e = scanPlain(s);
		if (e < 0) {
			throw BAIL;
		}
		String v = new String(b, s, e - s, StandardCharsets.ISO_8859_1);
		p = e + 1;
		skipWs();
		return v;
	}

	/**
	 * Index of the closing quote if {@code [s, quote)} is printable ASCII with no
	 * backslash; -1 if a backslash, control byte or non-ASCII byte comes first (or
	 * the string is unterminated).
	 */
	private int scanPlain(int s) {
		int e = plainStringEnd(b, s);
		return e < end ? e : -1; // -1 (not plain) and a quote beyond the slice both give -1
	}

	private String stringSlow(int s) {
		final byte[] b = this.b;
		final int end = this.end;
		// pass 1: find the closing quote, validating escapes and UTF-8 on the way
		int e = s;
		boolean escapes = false;
		while (true) {
			if (e >= end) {
				throw BAIL;
			}
			byte c = b[e];
			if (c == '"') {
				break;
			}
			if (c == '\\') {
				escapes = true;
				if (e + 1 >= end) {
					throw BAIL;
				}
				byte n = b[e + 1];
				if (n == 'u') {
					if (e + 6 > end || hex4(e + 2) < 0) {
						throw BAIL;
					}
					e += 6;
				} else if (n == '"' || n == '\\' || n == '/' || n == 'b' || n == 'f' || n == 'n' || n == 'r'
						|| n == 't') {
					e += 2;
				} else {
					throw BAIL;
				}
			} else if (c >= 0) {
				if (c < 0x20) {
					throw BAIL; // raw control character
				}
				e++;
			} else if (latin1) {
				e++; // the bytes of a Latin-1 String are its characters
			} else {
				e += utf8Length(b, e, end);
			}
		}
		if (!escapes) {
			// validated above, so the JDK decoder (which would replace malformed input)
			// sees
			// only well-formed UTF-8 and produces exactly the characters of the text
			String v = new String(b, s, e - s, latin1 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
			p = e + 1;
			skipWs();
			return v;
		}
		// pass 2: decode into chars (never more chars than bytes)
		char[] out = new char[e - s];
		int n = 0;
		int i = s;
		while (i < e) {
			int c = b[i];
			if (c >= 0) {
				if (c == '\\') {
					int esc = b[i + 1];
					switch (esc) {
						case 'u' -> {
							out[n++] = (char) hex4(i + 2);
							i += 6;
						}
						case 'b' -> {
							out[n++] = '\b';
							i += 2;
						}
						case 'f' -> {
							out[n++] = '\f';
							i += 2;
						}
						case 'n' -> {
							out[n++] = '\n';
							i += 2;
						}
						case 'r' -> {
							out[n++] = '\r';
							i += 2;
						}
						case 't' -> {
							out[n++] = '\t';
							i += 2;
						}
						default -> { // '"', '\\', '/'
							out[n++] = (char) esc;
							i += 2;
						}
					}
				} else {
					out[n++] = (char) c;
					i++;
				}
				continue;
			}
			int b0 = c & 0xFF;
			if (latin1) {
				out[n++] = (char) b0;
				i++;
			} else if (b0 <= 0xDF) {
				out[n++] = (char) (((b0 & 0x1F) << 6) | (b[i + 1] & 0x3F));
				i += 2;
			} else if (b0 <= 0xEF) {
				out[n++] = (char) (((b0 & 0x0F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F));
				i += 3;
			} else {
				int cp = ((b0 & 0x07) << 18) | ((b[i + 1] & 0x3F) << 12) | ((b[i + 2] & 0x3F) << 6) | (b[i + 3] & 0x3F);
				out[n++] = Character.highSurrogate(cp);
				out[n++] = Character.lowSurrogate(cp);
				i += 4;
			}
		}
		p = e + 1;
		skipWs();
		return new String(out, 0, n);
	}

	/**
	 * Length of the well-formed UTF-8 sequence starting at {@code i} (whose lead
	 * byte is 0x80 or more); bails on anything else: a stray continuation byte,
	 * overlong forms, surrogates, code points above U+10FFFF, truncation.
	 */
	private static int utf8Length(byte[] b, int i, int end) {
		int b0 = b[i] & 0xFF;
		if (b0 >= 0xC2 && b0 <= 0xDF) {
			if (i + 1 >= end || (b[i + 1] & 0xC0) != 0x80) {
				throw BAIL;
			}
			return 2;
		}
		if (b0 >= 0xE0 && b0 <= 0xEF) {
			if (i + 2 >= end) {
				throw BAIL;
			}
			int b1 = b[i + 1] & 0xFF;
			int b2 = b[i + 2] & 0xFF;
			if ((b1 & 0xC0) != 0x80 || (b2 & 0xC0) != 0x80 || (b0 == 0xE0 && b1 < 0xA0) || (b0 == 0xED && b1 >= 0xA0)) {
				throw BAIL;
			}
			return 3;
		}
		if (b0 >= 0xF0 && b0 <= 0xF4) {
			if (i + 3 >= end) {
				throw BAIL;
			}
			int b1 = b[i + 1] & 0xFF;
			int b2 = b[i + 2] & 0xFF;
			int b3 = b[i + 3] & 0xFF;
			if ((b1 & 0xC0) != 0x80 || (b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80 || (b0 == 0xF0 && b1 < 0x90)
					|| (b0 == 0xF4 && b1 >= 0x90)) {
				throw BAIL;
			}
			return 4;
		}
		throw BAIL;
	}

	/** Four hex digits at {@code at}, or -1. */
	private int hex4(int at) {
		int v = 0;
		for (int k = 0; k < 4; k++) {
			int c = b[at + k];
			int d;
			if (c >= '0' && c <= '9') {
				d = c - '0';
			} else if (c >= 'a' && c <= 'f') {
				d = c - 'a' + 10;
			} else if (c >= 'A' && c <= 'F') {
				d = c - 'A' + 10;
			} else {
				return -1;
			}
			v = (v << 4) | d;
		}
		return v;
	}

	/** A base64 string value, decoded exactly as the general decoder does. */
	public ByteString bytes() {
		final int i = p;
		if (i >= end || b[i] != '"') {
			throw BAIL;
		}
		int s = i + 1;
		int e = scanPlain(s);
		if (e < 0) {
			throw BAIL;
		}
		byte[] decoded;
		try {
			decoded = BASE64.decode(Arrays.copyOfRange(b, s, e));
		} catch (IllegalArgumentException invalidBase64) {
			throw BAIL;
		}
		p = e + 1;
		skipWs();
		// The array was allocated by the decoder just now and is referenced nowhere
		// else.
		return UnsafeByteOperations.unsafeWrap(decoded);
	}

	// ------------------------------------------------------------------- map keys

	/** A quoted canonical int32 map key. */
	public int int32Key() {
		long r = int32KeyAt(b, p);
		keyEnd((int) r);
		return (int) (r >> 32);
	}

	public int uint32Key() {
		long r = uint32KeyAt(b, p);
		keyEnd((int) r);
		return (int) (r >> 32);
	}

	/**
	 * After a key: whatever follows the closing quote is checked by the caller
	 * (colon()).
	 */
	private void keyEnd(int next) {
		if (next > end) {
			throw BAIL;
		}
		p = next;
		skipWs();
	}

	public long int64Key() {
		return quotedKey();
	}

	public long uint64Key() {
		return unsigned64(true);
	}

	public boolean boolKey() {
		int i = p;
		boolean v;
		if (matches(i, "\"true\"")) {
			v = true;
			i += 6;
		} else if (matches(i, "\"false\"")) {
			v = false;
			i += 7;
		} else {
			throw BAIL;
		}
		p = i;
		skipWs();
		return v;
	}

	private long quotedKey() {
		if (p >= end || b[p] != '"') {
			throw BAIL;
		}
		return integer(true);
	}

	// -------------------------------------------------- Timestamp / Duration
	// values

	/**
	 * A canonical RFC 3339 UTC timestamp,
	 * {@code YYYY-MM-DDTHH:MM:SS[.fff[fff[fff]]]Z}, inside the proto3 range.
	 * Offsets, other precisions, leap seconds and lowercase separators bail to the
	 * general parser.
	 */
	public Timestamp timestamp() {
		final byte[] b = this.b;
		final int q = p;
		if (q + 22 > end || b[q] != '"') {
			throw BAIL;
		}
		final int s = q + 1;
		int fraction; // digits after the '.'
		if (b[s + 19] == 'Z') {
			fraction = 0;
		} else if (b[s + 19] == '.') {
			int k = s + 20;
			while (k < end && b[k] >= '0' && b[k] <= '9') {
				k++;
			}
			fraction = k - (s + 20);
			if ((fraction != 3 && fraction != 6 && fraction != 9) || k >= end || b[k] != 'Z') {
				throw BAIL;
			}
		} else {
			throw BAIL;
		}
		int zAt = s + 19 + (fraction == 0 ? 0 : fraction + 1);
		if (zAt + 1 >= end || b[zAt + 1] != '"' || !delimiterAt(zAt + 2) || b[s + 4] != '-' || b[s + 7] != '-'
				|| b[s + 10] != 'T' || b[s + 13] != ':' || b[s + 16] != ':') {
			throw BAIL;
		}
		int year = digits(s, 4);
		int month = digits(s + 5, 2);
		int day = digits(s + 8, 2);
		int hour = digits(s + 11, 2);
		int minute = digits(s + 14, 2);
		int second = digits(s + 17, 2);
		if (year < 1 || month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month) || hour > 23 || minute > 59
				|| second > 59) {
			throw BAIL;
		}
		int nanos = 0;
		if (fraction != 0) {
			nanos = digits(s + 20, fraction);
			for (int k = fraction; k < 9; k++) {
				nanos *= 10;
			}
		}
		long seconds = epochDay(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second;
		p = zAt + 2;
		skipWs();
		return Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
	}

	/**
	 * A canonical duration string: {@code -?digits[.d{1,9}]s} within +-315576000000
	 * seconds.
	 */
	public Duration duration() {
		final byte[] b = this.b;
		int i = p;
		if (i >= end || b[i] != '"') {
			throw BAIL;
		}
		i++;
		boolean negative = false;
		if (i < end && b[i] == '-') {
			negative = true;
			i++;
		}
		int start = i;
		long seconds = 0;
		while (i < end && b[i] >= '0' && b[i] <= '9') {
			seconds = seconds * 10 + (b[i] - '0');
			i++;
			if (i - start > 12) {
				throw BAIL;
			}
		}
		if (i == start || (i - start > 1 && b[start] == '0')) {
			throw BAIL;
		}
		int nanos = 0;
		if (i < end && b[i] == '.') {
			i++;
			int fracStart = i;
			while (i < end && b[i] >= '0' && b[i] <= '9') {
				nanos = nanos * 10 + (b[i] - '0');
				i++;
				if (i - fracStart > 9) {
					throw BAIL;
				}
			}
			int fractionDigits = i - fracStart;
			if (fractionDigits == 0) {
				throw BAIL;
			}
			for (int k = fractionDigits; k < 9; k++) {
				nanos *= 10;
			}
		}
		if (i + 1 >= end || b[i] != 's' || b[i + 1] != '"' || !delimiterAt(i + 2)) {
			throw BAIL;
		}
		if (seconds > 315_576_000_000L) {
			throw BAIL;
		}
		if (negative) {
			seconds = -seconds;
			nanos = -nanos;
		}
		p = i + 2;
		skipWs();
		return Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
	}

	private int digits(int at, int count) {
		int v = 0;
		for (int k = 0; k < count; k++) {
			int d = b[at + k] - '0';
			if (d < 0 || d > 9) {
				throw BAIL;
			}
			v = v * 10 + d;
		}
		return v;
	}

	private static int daysInMonth(int year, int month) {
		return switch (month) {
			case 2 -> (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28;
			case 4, 6, 9, 11 -> 30;
			default -> 31;
		};
	}

	/**
	 * Days since 1970-01-01 of a proleptic Gregorian date (Hinnant's
	 * days_from_civil).
	 */
	private static long epochDay(int year, int month, int day) {
		int y = month <= 2 ? year - 1 : year;
		int era = y / 400; // year >= 1, so y >= 0
		int yoe = y - era * 400;
		int doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
		int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
		return era * 146_097L + doe - 719_468L;
	}

	// --------------------------------------------------------------- skipping
	// values

	/** Skips one JSON value of any kind, validating its structure. */
	public void skipValue() {
		skipValue(0);
	}

	private void skipValue(int level) {
		if (p >= end) {
			throw BAIL;
		}
		switch (b[p]) {
			case '"' -> {
				int s = p + 1;
				int e = scanPlain(s);
				if (e >= 0) {
					p = e + 1;
					skipWs();
				} else {
					stringSlow(s);
				}
			}
			case '{' -> {
				if (level >= MAX_SKIP_DEPTH) {
					throw BAIL;
				}
				objectStart();
				if (!objectEnd()) {
					do {
						string();
						colon();
						skipValue(level + 1);
					} while (comma());
					objectEndRequired();
				}
			}
			case '[' -> {
				if (level >= MAX_SKIP_DEPTH) {
					throw BAIL;
				}
				arrayStart();
				if (!arrayEnd()) {
					do {
						skipValue(level + 1);
					} while (comma());
					arrayEndRequired();
				}
			}
			case 't' -> bool();
			case 'f' -> bool();
			case 'n' -> {
				if (!nullLiteral()) {
					throw BAIL;
				}
			}
			default -> number(false);
		}
	}
}
