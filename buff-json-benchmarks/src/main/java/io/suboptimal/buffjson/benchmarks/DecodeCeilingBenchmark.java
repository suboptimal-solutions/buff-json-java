package io.suboptimal.buffjson.benchmarks;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonDecoder;
import io.suboptimal.buffjson.proto.SimpleMessage;
import io.suboptimal.buffjson.proto.Status;

/**
 * Experiment: what would a protobuf-aware tokenizer that owns the input cursor
 * (instead of going through fastjson2's {@code JSONReader}) achieve on
 * {@code SimpleMessage}? {@link #buffJson} is the shipped decoder,
 * {@link #ceiling} a hand-written strict decoder over the same UTF-8 bytes. The
 * setup asserts that both produce identical messages for every fixture.
 *
 * <p>
 * The hand-written decoder is deliberately not an "unsafe benchmark trick": it
 * validates structure, digits and escapes, and falls back to the general
 * {@link BuffJsonDecoder} for anything it does not recognise (escapes, unusual
 * whitespace, aliases, unknown fields, exponent/edge numbers).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(2)
@State(Scope.Thread)
public class DecodeCeilingBenchmark {

	private static final int POOL = 1024;
	private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
	private static final BuffJsonDecoder DECODER = BuffJson.decoder();

	/**
	 * Random 17-digit doubles dominate any parser that lacks a correctly-rounded
	 * fast path.
	 */
	@Param({"true", "false"})
	public boolean withScore;

	private byte[][] bytes;
	private int index;

	@Setup
	public void setup() throws Exception {
		SimpleMessage[] messages = BenchmarkData.createRandomSimpleMessages(new Random(42), POOL);
		var printer = JsonFormat.printer().omittingInsignificantWhitespace();
		bytes = new byte[POOL][];
		for (int i = 0; i < POOL; i++) {
			if (!withScore) {
				messages[i] = messages[i].toBuilder().clearScore().build();
			}
			bytes[i] = printer.print(messages[i]).getBytes(StandardCharsets.UTF_8);
			SimpleMessage viaCeiling = decodeCeiling(bytes[i]);
			if (!messages[i].equals(viaCeiling)) {
				throw new IllegalStateException(
						"ceiling mismatch at " + i + ": " + new String(bytes[i]) + " -> " + viaCeiling);
			}
		}
	}

	@Benchmark
	public SimpleMessage buffJson() {
		return DECODER.decode(bytes[index++ & (POOL - 1)], SimpleMessage.class);
	}

	@Benchmark
	public SimpleMessage ceiling() {
		return decodeCeiling(bytes[index++ & (POOL - 1)]);
	}

	// ---------------------------------------------------------------------

	static SimpleMessage decodeCeiling(byte[] b) {
		SimpleMessage m;
		try {
			m = fast(b);
		} catch (RuntimeException truncatedOrMalformed) {
			m = null;
		}
		return m != null ? m : DECODER.decode(b, SimpleMessage.class);
	}

	private static SimpleMessage fast(byte[] b) {
		SimpleMessage.Builder builder = SimpleMessage.newBuilder();
		int n = b.length;
		int p = 0;
		if (n < 2 || b[0] != '{') {
			return null;
		}
		p = 1;
		if (b[p] == '}') {
			return p + 1 == n ? builder.build() : null;
		}
		while (true) {
			int prefix = (int) INT.get(b, p);
			switch (prefix) {
				case 0x6d616e22 -> { // "nam(e)
					if (b[p + 4] != 'e' || b[p + 5] != '"' || b[p + 6] != ':' || b[p + 7] != '"') {
						return null;
					}
					int s = p + 8;
					int e = s;
					while (e < n && b[e] != '"') {
						if (b[e] == '\\' || b[e] < 0x20 || b[e] < 0) {
							return null;
						}
						e++;
					}
					if (e >= n) {
						return null;
					}
					builder.setName(new String(b, s, e - s, StandardCharsets.ISO_8859_1));
					p = e + 1;
				}
				case 0x22646922 -> { // "id"
					if (b[p + 4] != ':') {
						return null;
					}
					int s = p + 5;
					int e = s;
					boolean neg = false;
					if (b[e] == '-') {
						neg = true;
						e++;
					}
					int ds = e;
					long v = 0;
					while (e < n && b[e] >= '0' && b[e] <= '9') {
						v = v * 10 + (b[e] - '0');
						e++;
					}
					int digits = e - ds;
					if (digits == 0 || digits > 10 || (digits > 1 && b[ds] == '0')) {
						return null;
					}
					if (neg) {
						v = -v;
					}
					if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
						return null;
					}
					builder.setId((int) v);
					p = e;
				}
				case 0x6d697422 -> { // "tim(estampMillis)
					if (!matches(b, p + 4, TIMESTAMP_TAIL)) {
						return null;
					}
					int s = p + 4 + TIMESTAMP_TAIL.length; // at opening quote of the value
					if (b[s] != '"') {
						return null;
					}
					s++;
					int e = s;
					boolean neg = false;
					if (b[e] == '-') {
						neg = true;
						e++;
					}
					int ds = e;
					long v = 0;
					while (e < n && b[e] >= '0' && b[e] <= '9') {
						v = v * 10 + (b[e] - '0');
						e++;
					}
					int digits = e - ds;
					if (digits == 0 || digits > 19 || e >= n || b[e] != '"' || (digits > 1 && b[ds] == '0')
							|| (digits == 19 && v < 0)) {
						return null;
					}
					builder.setTimestampMillis(neg ? -v : v);
					p = e + 1;
				}
				case 0x6f637322 -> { // "sco(re)
					if (b[p + 4] != 'r' || b[p + 5] != 'e' || b[p + 6] != '"' || b[p + 7] != ':') {
						return null;
					}
					int s = p + 8;
					int e = s;
					while (e < n && b[e] != ',' && b[e] != '}') {
						e++;
					}
					if (e >= n) {
						return null;
					}
					builder.setScore(parseDouble(b, s, e));
					p = e;
				}
				case 0x74636122 -> { // "act(ive)
					if (!matches(b, p + 4, ACTIVE_TAIL)) {
						return null;
					}
					int s = p + 4 + ACTIVE_TAIL.length;
					if (b[s] == 't' && b[s + 1] == 'r' && b[s + 2] == 'u' && b[s + 3] == 'e') {
						builder.setActive(true);
						p = s + 4;
					} else if (b[s] == 'f' && b[s + 1] == 'a' && b[s + 2] == 'l' && b[s + 3] == 's'
							&& b[s + 4] == 'e') {
						builder.setActive(false);
						p = s + 5;
					} else {
						return null;
					}
				}
				case 0x61747322 -> { // "sta(tus)
					if (!matches(b, p + 4, STATUS_TAIL)) {
						return null;
					}
					int s = p + 4 + STATUS_TAIL.length;
					if (b[s] != '"') {
						return null;
					}
					s++;
					int e = s;
					while (e < n && b[e] != '"') {
						e++;
					}
					int len = e - s;
					if (len == 13 && Arrays.equals(b, s, e, ACTIVE_NAME, 0, 13)) {
						builder.setStatusValue(Status.STATUS_ACTIVE_VALUE);
					} else if (len == 15 && Arrays.equals(b, s, e, INACTIVE_NAME, 0, 15)) {
						builder.setStatusValue(Status.STATUS_INACTIVE_VALUE);
					} else if (len == 18 && Arrays.equals(b, s, e, UNSPEC_NAME, 0, 18)) {
						builder.setStatusValue(Status.STATUS_UNSPECIFIED_VALUE);
					} else {
						return null;
					}
					p = e + 1;
				}
				default -> {
					return null;
				}
			}
			// separator
			if (p >= n) {
				return null;
			}
			byte c = b[p];
			if (c == ',') {
				p++;
			} else if (c == '}') {
				return p + 1 == n ? builder.build() : null;
			} else {
				return null;
			}
		}
	}

	private static final byte[] TIMESTAMP_TAIL = "estampMillis\":".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] ACTIVE_TAIL = "ive\":".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] STATUS_TAIL = "tus\":".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] ACTIVE_NAME = "STATUS_ACTIVE".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] INACTIVE_NAME = "STATUS_INACTIVE".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] UNSPEC_NAME = "STATUS_UNSPECIFIED".getBytes(StandardCharsets.US_ASCII);
	private static final double[] POW10 = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13,
			1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};

	private static boolean matches(byte[] b, int from, byte[] expected) {
		return from + expected.length < b.length
				&& Arrays.equals(b, from, from + expected.length, expected, 0, expected.length);
	}

	/**
	 * Clinger fast path (<= 15 significant digits, small exponent); otherwise the
	 * JDK parser.
	 */
	private static double parseDouble(byte[] b, int s, int e) {
		int i = s;
		boolean neg = false;
		if (b[i] == '-') {
			neg = true;
			i++;
		}
		long mant = 0;
		int digits = 0;
		int scale = 0;
		boolean seenDot = false;
		boolean plain = true;
		for (; i < e; i++) {
			byte c = b[i];
			if (c >= '0' && c <= '9') {
				if (digits < 19) {
					mant = mant * 10 + (c - '0');
					if (mant != 0) {
						digits++;
					}
					if (seenDot) {
						scale++;
					}
				} else {
					plain = false;
				}
			} else if (c == '.' && !seenDot) {
				seenDot = true;
			} else {
				plain = false;
				break;
			}
		}
		if (plain && digits <= 15 && scale <= 22 && i == e) {
			double d = scale == 0 ? (double) mant : mant / POW10[scale];
			return neg ? -d : d;
		}
		return Double.parseDouble(new String(b, s, e - s, StandardCharsets.ISO_8859_1));
	}
}
