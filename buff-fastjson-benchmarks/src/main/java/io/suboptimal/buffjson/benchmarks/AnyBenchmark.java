package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Timestamp;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJSON;
import io.suboptimal.buffjson.Encoder;
import io.suboptimal.buffjson.proto.BenchAllScalars;
import io.suboptimal.buffjson.proto.BenchAny;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class AnyBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final TypeRegistry TYPE_REGISTRY = TypeRegistry.newBuilder().add(BenchAllScalars.getDescriptor())
			.add(Timestamp.getDescriptor()).build();
	private static final Encoder CODEGEN_ENCODER = BuffJSON.encoder().withTypeRegistry(TYPE_REGISTRY);
	private static final Encoder GENERIC_ENCODER = CODEGEN_ENCODER.withGeneratedEncoders(false);
	private static final JsonFormat.Printer PROTO_PRINTER = JsonFormat.printer().usingTypeRegistry(TYPE_REGISTRY);

	private BenchAny anyScalar;
	private BenchAny[] randomAnyScalars;
	private BenchAny anyTimestamp;
	private BenchAny[] randomAnyTimestamps;
	private int index;

	@Setup
	public void setup() {
		anyScalar = BenchmarkData.createBenchAnyWithScalars();
		randomAnyScalars = BenchmarkData.createRandomBenchAnyWithScalars(new Random(42), POOL_SIZE);
		anyTimestamp = BenchmarkData.createBenchAnyWithTimestamp();
		randomAnyTimestamps = BenchmarkData.createRandomBenchAnyWithTimestamp(new Random(43), POOL_SIZE);
	}

	@Benchmark
	public String anyScalarCodegen() {
		return CODEGEN_ENCODER.encode(anyScalar);
	}

	@Benchmark
	public String anyScalarCodegenRandom() {
		return CODEGEN_ENCODER.encode(randomAnyScalars[index++ & MASK]);
	}

	@Benchmark
	public String anyScalarGeneric() {
		return GENERIC_ENCODER.encode(anyScalar);
	}

	@Benchmark
	public String anyScalarGenericRandom() {
		return GENERIC_ENCODER.encode(randomAnyScalars[index++ & MASK]);
	}

	@Benchmark
	public String anyScalarJsonFormat() throws Exception {
		return PROTO_PRINTER.print(anyScalar);
	}

	@Benchmark
	public String anyScalarJsonFormatRandom() throws Exception {
		return PROTO_PRINTER.print(randomAnyScalars[index++ & MASK]);
	}

	@Benchmark
	public String anyTimestampCodegen() {
		return CODEGEN_ENCODER.encode(anyTimestamp);
	}

	@Benchmark
	public String anyTimestampCodegenRandom() {
		return CODEGEN_ENCODER.encode(randomAnyTimestamps[index++ & MASK]);
	}

	@Benchmark
	public String anyTimestampGeneric() {
		return GENERIC_ENCODER.encode(anyTimestamp);
	}

	@Benchmark
	public String anyTimestampGenericRandom() {
		return GENERIC_ENCODER.encode(randomAnyTimestamps[index++ & MASK]);
	}

	@Benchmark
	public String anyTimestampJsonFormat() throws Exception {
		return PROTO_PRINTER.print(anyTimestamp);
	}

	@Benchmark
	public String anyTimestampJsonFormatRandom() throws Exception {
		return PROTO_PRINTER.print(randomAnyTimestamps[index++ & MASK]);
	}
}
