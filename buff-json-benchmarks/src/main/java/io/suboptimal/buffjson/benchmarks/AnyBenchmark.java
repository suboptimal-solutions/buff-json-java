package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Timestamp;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonEncoder;
import io.suboptimal.buffjson.proto.BenchAllScalars;
import io.suboptimal.buffjson.proto.BenchAny;

/**
 * Full-profile benchmark: Any type with TypeRegistry lookup and dynamic
 * unpacking.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Benchmark)
public class AnyBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final TypeRegistry TYPE_REGISTRY = TypeRegistry.newBuilder().add(BenchAllScalars.getDescriptor())
			.add(Timestamp.getDescriptor()).build();
	private static final BuffJsonEncoder COMPILED_ENCODER = BuffJson.encoder().setTypeRegistry(TYPE_REGISTRY);
	private static final BuffJsonEncoder RUNTIME_ENCODER = COMPILED_ENCODER.setGeneratedEncoders(false);
	private static final JsonFormat.Printer PROTO_PRINTER = JsonFormat.printer().usingTypeRegistry(TYPE_REGISTRY);

	private BenchAny[] randomAnyScalars;
	private BenchAny[] randomAnyTimestamps;
	private int index;

	@Setup
	public void setup() {
		randomAnyScalars = BenchmarkData.createRandomBenchAnyWithScalars(new Random(42), POOL_SIZE);
		randomAnyTimestamps = BenchmarkData.createRandomBenchAnyWithTimestamp(new Random(43), POOL_SIZE);
	}

	@Benchmark
	public String anyScalarCompiled() {
		return COMPILED_ENCODER.encode(randomAnyScalars[index++ & MASK]);
	}

	@Benchmark
	public String anyScalarRuntime() {
		return RUNTIME_ENCODER.encode(randomAnyScalars[index++ & MASK]);
	}

	@Benchmark
	public String anyScalarJsonFormat() throws Exception {
		return PROTO_PRINTER.print(randomAnyScalars[index++ & MASK]);
	}

	@Benchmark
	public String anyTimestampCompiled() {
		return COMPILED_ENCODER.encode(randomAnyTimestamps[index++ & MASK]);
	}

	@Benchmark
	public String anyTimestampRuntime() {
		return RUNTIME_ENCODER.encode(randomAnyTimestamps[index++ & MASK]);
	}

	@Benchmark
	public String anyTimestampJsonFormat() throws Exception {
		return PROTO_PRINTER.print(randomAnyTimestamps[index++ & MASK]);
	}
}
