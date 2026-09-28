package io.suboptimal.buffjson.benchmarks;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson2.JSONReader;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import io.suboptimal.buffjson.internal.FieldReader;

/**
 * Isolates the cost of reading a run of integers out of a JSON array with
 * different strategies, to attribute decode time to number parsing.
 *
 * <ul>
 * <li>{@code strictCurrent} - {@link FieldReader#readStrictInt32}: what the
 * decoders do today (isNumber + readDoubleValue + rint + range check)
 * <li>{@code fastjsonRaw} - {@code JSONReader.readInt32Value()}: fastjson2's
 * integer fast path (lenient on non-canonical input, so NOT spec-safe on its
 * own)
 * <li>{@code ownCursor} - a strict hand-written digit loop over the byte[]
 * (what a protobuf-aware cursor could do)
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(2)
@State(Scope.Thread)
public class NumberReadBenchmark {

	private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

	@Param({"small", "ten"})
	public String digits;

	private static final int COUNT = 128;
	private byte[] json;

	@Setup
	public void setup() {
		Random rng = new Random(7);
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < COUNT; i++) {
			if (i > 0) {
				sb.append(',');
			}
			int v = switch (digits) {
				case "small" -> rng.nextInt(100_000); // typical ids / counts
				default -> rng.nextInt(); // worst case: up to 10 digits + sign
			};
			sb.append(v);
		}
		json = sb.append(']').toString().getBytes(StandardCharsets.UTF_8);
	}

	/** Per-element cost: divide the reported time by 128. */
	@Benchmark
	public void strictCurrent(Blackhole bh) {
		try (JSONReader r = JSONReader.of(json)) {
			r.nextIfArrayStart();
			while (!r.nextIfArrayEnd()) {
				bh.consume(FieldReader.readStrictInt32(r));
			}
		}
	}

	@Benchmark
	public void fastjsonRaw(Blackhole bh) {
		try (JSONReader r = JSONReader.of(json)) {
			r.nextIfArrayStart();
			while (!r.nextIfArrayEnd()) {
				bh.consume(r.readInt32Value());
			}
		}
	}

	@Benchmark
	public void ownCursor(Blackhole bh) {
		byte[] b = json;
		int p = 1;
		int n = b.length;
		while (b[p] != ']') {
			boolean neg = b[p] == '-';
			if (neg) {
				p++;
			}
			int start = p;
			long v = 0;
			while (b[p] >= '0' && b[p] <= '9') {
				v = v * 10 + (b[p] - '0');
				p++;
			}
			int len = p - start;
			if (len == 0 || len > 10 || (len > 1 && b[start] == '0')) {
				throw new IllegalStateException();
			}
			if (neg) {
				v = -v;
			}
			if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
				throw new IllegalStateException();
			}
			bh.consume((int) v);
			if (b[p] == ',') {
				p++;
			}
		}
		bh.consume(n);
	}

	/** SWAR: 8 digits at a time (only meaningful for the long-number case). */
	@Benchmark
	public void ownCursorSwar(Blackhole bh) {
		byte[] b = json;
		int p = 1;
		while (b[p] != ']') {
			boolean neg = b[p] == '-';
			if (neg) {
				p++;
			}
			int start = p;
			long v;
			if (p + 8 <= b.length) {
				long w = (long) LONG.get(b, p);
				// digits check: every byte in '0'..'9'
				long t = w - 0x3030303030303030L;
				long bad = ((t + 0x7676767676767676L) | t) & 0x8080808080808080L;
				if (bad == 0) { // 8 leading digits
					t = (t * 10) + (t >>> 8);
					t = (((t & 0x000000FF000000FFL) * (100 + (1000000L << 32)))
							+ (((t >>> 16) & 0x000000FF000000FFL) * (1 + (10000L << 32)))) >>> 32;
					v = t;
					p += 8;
					while (b[p] >= '0' && b[p] <= '9') {
						v = v * 10 + (b[p] - '0');
						p++;
					}
				} else {
					v = 0;
					while (b[p] >= '0' && b[p] <= '9') {
						v = v * 10 + (b[p] - '0');
						p++;
					}
				}
			} else {
				v = 0;
				while (b[p] >= '0' && b[p] <= '9') {
					v = v * 10 + (b[p] - '0');
					p++;
				}
			}
			if (p == start) {
				throw new IllegalStateException();
			}
			bh.consume((int) (neg ? -v : v));
			if (b[p] == ',') {
				p++;
			}
		}
	}
}
