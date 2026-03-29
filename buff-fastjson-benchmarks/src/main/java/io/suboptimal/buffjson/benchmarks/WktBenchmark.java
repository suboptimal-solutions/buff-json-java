package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJSON;
import io.suboptimal.buffjson.Encoder;
import io.suboptimal.buffjson.proto.BenchDurations;
import io.suboptimal.buffjson.proto.BenchStruct;
import io.suboptimal.buffjson.proto.BenchTimestamps;
import io.suboptimal.buffjson.proto.BenchWrappers;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class WktBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final JsonFormat.Printer PROTO_PRINTER = JsonFormat.printer();
	private static final Encoder GENERIC_ENCODER = BuffJSON.encoder().withGeneratedEncoders(false);

	private BenchTimestamps timestamps;
	private BenchTimestamps[] randomTimestamps;
	private BenchDurations durations;
	private BenchDurations[] randomDurations;
	private BenchWrappers wrappers;
	private BenchWrappers[] randomWrappers;
	private BenchStruct struct;
	private BenchStruct[] randomStructs;
	private int index;

	@Setup
	public void setup() {
		timestamps = BenchmarkData.createBenchTimestamps();
		randomTimestamps = BenchmarkData.createRandomBenchTimestamps(new Random(42), POOL_SIZE);
		durations = BenchmarkData.createBenchDurations();
		randomDurations = BenchmarkData.createRandomBenchDurations(new Random(43), POOL_SIZE);
		wrappers = BenchmarkData.createBenchWrappers();
		randomWrappers = BenchmarkData.createRandomBenchWrappers(new Random(44), POOL_SIZE);
		struct = BenchmarkData.createBenchStruct();
		randomStructs = BenchmarkData.createRandomBenchStructs(new Random(45), POOL_SIZE);
	}

	@Benchmark
	public String timestampCodegen() {
		return BuffJSON.encode(timestamps);
	}

	@Benchmark
	public String timestampCodegenRandom() {
		return BuffJSON.encode(randomTimestamps[index++ & MASK]);
	}

	@Benchmark
	public String timestampGeneric() {
		return GENERIC_ENCODER.encode(timestamps);
	}

	@Benchmark
	public String timestampGenericRandom() {
		return GENERIC_ENCODER.encode(randomTimestamps[index++ & MASK]);
	}

	@Benchmark
	public String timestampJsonFormat() throws Exception {
		return PROTO_PRINTER.print(timestamps);
	}

	@Benchmark
	public String timestampJsonFormatRandom() throws Exception {
		return PROTO_PRINTER.print(randomTimestamps[index++ & MASK]);
	}

	@Benchmark
	public String durationCodegen() {
		return BuffJSON.encode(durations);
	}

	@Benchmark
	public String durationCodegenRandom() {
		return BuffJSON.encode(randomDurations[index++ & MASK]);
	}

	@Benchmark
	public String durationGeneric() {
		return GENERIC_ENCODER.encode(durations);
	}

	@Benchmark
	public String durationGenericRandom() {
		return GENERIC_ENCODER.encode(randomDurations[index++ & MASK]);
	}

	@Benchmark
	public String durationJsonFormat() throws Exception {
		return PROTO_PRINTER.print(durations);
	}

	@Benchmark
	public String durationJsonFormatRandom() throws Exception {
		return PROTO_PRINTER.print(randomDurations[index++ & MASK]);
	}

	@Benchmark
	public String wrappersCodegen() {
		return BuffJSON.encode(wrappers);
	}

	@Benchmark
	public String wrappersCodegenRandom() {
		return BuffJSON.encode(randomWrappers[index++ & MASK]);
	}

	@Benchmark
	public String wrappersGeneric() {
		return GENERIC_ENCODER.encode(wrappers);
	}

	@Benchmark
	public String wrappersGenericRandom() {
		return GENERIC_ENCODER.encode(randomWrappers[index++ & MASK]);
	}

	@Benchmark
	public String wrappersJsonFormat() throws Exception {
		return PROTO_PRINTER.print(wrappers);
	}

	@Benchmark
	public String wrappersJsonFormatRandom() throws Exception {
		return PROTO_PRINTER.print(randomWrappers[index++ & MASK]);
	}

	@Benchmark
	public String structCodegen() {
		return BuffJSON.encode(struct);
	}

	@Benchmark
	public String structCodegenRandom() {
		return BuffJSON.encode(randomStructs[index++ & MASK]);
	}

	@Benchmark
	public String structGeneric() {
		return GENERIC_ENCODER.encode(struct);
	}

	@Benchmark
	public String structGenericRandom() {
		return GENERIC_ENCODER.encode(randomStructs[index++ & MASK]);
	}

	@Benchmark
	public String structJsonFormat() throws Exception {
		return PROTO_PRINTER.print(struct);
	}

	@Benchmark
	public String structJsonFormatRandom() throws Exception {
		return PROTO_PRINTER.print(randomStructs[index++ & MASK]);
	}
}
