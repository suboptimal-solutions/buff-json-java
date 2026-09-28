package io.suboptimal.buffjson.benchmarks;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson2.JSONFactory;
import com.alibaba.fastjson2.JSONReader;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonDecoder;
import io.suboptimal.buffjson.proto.SimpleMessage;

/**
 * Fixed per-call cost of decoding through fastjson2, independent of message
 * content: creating and closing a reader, and the {@link BuffJsonDecoder}
 * wrapper around it.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(2)
@State(Scope.Thread)
public class DecodeOverheadBenchmark {

	private static final BuffJsonDecoder DECODER = BuffJson.decoder();
	private static final JSONReader.Context SHARED_CONTEXT = JSONFactory.createReadContext();

	private final byte[] empty = "{}".getBytes(StandardCharsets.UTF_8);
	private final byte[] oneField = "{\"id\":12345}".getBytes(StandardCharsets.UTF_8);
	private final String emptyString = "{}";

	/** Only the reader: of(byte[]) + close(). */
	@Benchmark
	public void readerOnlyBytes(Blackhole bh) {
		try (JSONReader r = JSONReader.of(empty)) {
			bh.consume(r.nextIfObjectStart());
		}
	}

	@Benchmark
	public void readerOnlyBytesSharedContext(Blackhole bh) {
		try (JSONReader r = JSONReader.of(empty, 0, empty.length, StandardCharsets.UTF_8, SHARED_CONTEXT)) {
			bh.consume(r.nextIfObjectStart());
		}
	}

	@Benchmark
	public void readerOnlyString(Blackhole bh) {
		try (JSONReader r = JSONReader.of(emptyString)) {
			bh.consume(r.nextIfObjectStart());
		}
	}

	/**
	 * The whole decode of an empty message: reader + dispatch + Builder + build().
	 */
	@Benchmark
	public SimpleMessage decodeEmptyBytes() {
		return DECODER.decode(empty, SimpleMessage.class);
	}

	@Benchmark
	public SimpleMessage decodeEmptyString() {
		return DECODER.decode(emptyString, SimpleMessage.class);
	}

	@Benchmark
	public SimpleMessage decodeOneFieldBytes() {
		return DECODER.decode(oneField, SimpleMessage.class);
	}

	/** Floor for the protobuf side: builder + message, no JSON at all. */
	@Benchmark
	public SimpleMessage builderOnly() {
		return SimpleMessage.newBuilder().build();
	}

	@Benchmark
	public SimpleMessage builderOneField() {
		return SimpleMessage.newBuilder().setId(12345).build();
	}
}
