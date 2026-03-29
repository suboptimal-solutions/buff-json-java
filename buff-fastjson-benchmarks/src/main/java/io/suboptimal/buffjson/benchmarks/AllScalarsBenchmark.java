package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJSON;
import io.suboptimal.buffjson.Encoder;
import io.suboptimal.buffjson.proto.BenchAllScalars;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class AllScalarsBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final JsonFormat.Printer PROTO_PRINTER = JsonFormat.printer();
	private static final Encoder GENERIC_ENCODER = BuffJSON.encoder().withGeneratedEncoders(false);

	private BenchAllScalars message;
	private BenchAllScalars[] randomMessages;
	private int index;

	@Setup
	public void setup() {
		message = BenchmarkData.createBenchAllScalars();
		randomMessages = BenchmarkData.createRandomBenchAllScalars(new Random(42), POOL_SIZE);
	}

	@Benchmark
	public String buffJsonCodegen() {
		return BuffJSON.encode(message);
	}

	@Benchmark
	public String buffJsonCodegenRandom() {
		return BuffJSON.encode(randomMessages[index++ & MASK]);
	}

	@Benchmark
	public String buffJson() {
		return GENERIC_ENCODER.encode(message);
	}

	@Benchmark
	public String buffJsonRandom() {
		return GENERIC_ENCODER.encode(randomMessages[index++ & MASK]);
	}

	@Benchmark
	public String protoJsonFormat() throws Exception {
		return PROTO_PRINTER.print(message);
	}

	@Benchmark
	public String protoJsonFormatRandom() throws Exception {
		return PROTO_PRINTER.print(randomMessages[index++ & MASK]);
	}
}
