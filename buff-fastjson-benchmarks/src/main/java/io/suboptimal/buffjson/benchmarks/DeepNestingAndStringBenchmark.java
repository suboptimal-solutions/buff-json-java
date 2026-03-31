package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJSON;
import io.suboptimal.buffjson.Encoder;
import io.suboptimal.buffjson.proto.BenchDeepNesting;
import io.suboptimal.buffjson.proto.BenchStringHeavy;

/**
 * Full-profile benchmark: recursive nesting (3-7 levels) and large
 * strings/bytes (1-5KB).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Benchmark)
public class DeepNestingAndStringBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final JsonFormat.Printer PROTO_PRINTER = JsonFormat.printer();
	private static final Encoder RUNTIME_ENCODER = BuffJSON.encoder().withGeneratedEncoders(false);

	private BenchDeepNesting[] randomDeepNesting;
	private BenchStringHeavy[] randomStringHeavy;
	private int index;

	@Setup
	public void setup() {
		randomDeepNesting = BenchmarkData.createRandomBenchDeepNesting(new Random(42), POOL_SIZE);
		randomStringHeavy = BenchmarkData.createRandomBenchStringHeavy(new Random(43), POOL_SIZE);
	}

	@Benchmark
	public String deepNestingCompiled() {
		return BuffJSON.encode(randomDeepNesting[index++ & MASK]);
	}

	@Benchmark
	public String deepNestingRuntime() {
		return RUNTIME_ENCODER.encode(randomDeepNesting[index++ & MASK]);
	}

	@Benchmark
	public String deepNestingJsonFormat() throws Exception {
		return PROTO_PRINTER.print(randomDeepNesting[index++ & MASK]);
	}

	@Benchmark
	public String stringHeavyCompiled() {
		return BuffJSON.encode(randomStringHeavy[index++ & MASK]);
	}

	@Benchmark
	public String stringHeavyRuntime() {
		return RUNTIME_ENCODER.encode(randomStringHeavy[index++ & MASK]);
	}

	@Benchmark
	public String stringHeavyJsonFormat() throws Exception {
		return PROTO_PRINTER.print(randomStringHeavy[index++ & MASK]);
	}
}
