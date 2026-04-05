package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonEncoder;
import io.suboptimal.buffjson.proto.BenchStruct;
import io.suboptimal.buffjson.proto.BenchTimestamps;

/**
 * Regression benchmark: well-known types (Timestamp = most used WKT, Struct =
 * hardest code path).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Benchmark)
public class WktBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final JsonFormat.Printer PROTO_PRINTER = JsonFormat.printer();
	private static final BuffJsonEncoder BUFF_JSON = BuffJson.encoder();
	private static final BuffJsonEncoder RUNTIME_ENCODER = BuffJson.encoder().setGeneratedEncoders(false);

	private BenchTimestamps[] randomTimestamps;
	private BenchStruct[] randomStructs;
	private int index;

	@Setup
	public void setup() {
		randomTimestamps = BenchmarkData.createRandomBenchTimestamps(new Random(42), POOL_SIZE);
		randomStructs = BenchmarkData.createRandomBenchStructs(new Random(45), POOL_SIZE);
	}

	@Benchmark
	public String timestampCompiled() {
		return BUFF_JSON.encode(randomTimestamps[index++ & MASK]);
	}

	@Benchmark
	public String timestampRuntime() {
		return RUNTIME_ENCODER.encode(randomTimestamps[index++ & MASK]);
	}

	@Benchmark
	public String timestampJsonFormat() throws Exception {
		return PROTO_PRINTER.print(randomTimestamps[index++ & MASK]);
	}

	@Benchmark
	public String structCompiled() {
		return BUFF_JSON.encode(randomStructs[index++ & MASK]);
	}

	@Benchmark
	public String structRuntime() {
		return RUNTIME_ENCODER.encode(randomStructs[index++ & MASK]);
	}

	@Benchmark
	public String structJsonFormat() throws Exception {
		return PROTO_PRINTER.print(randomStructs[index++ & MASK]);
	}
}
