package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJSON;
import io.suboptimal.buffjson.Encoder;
import io.suboptimal.buffjson.proto.BenchMapHeavy;
import io.suboptimal.buffjson.proto.BenchRepeatedHeavy;

/**
 * Regression benchmark: scaling with collection size (100+ repeated elements,
 * 50+ map entries).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Benchmark)
public class RepeatedAndMapBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final JsonFormat.Printer PROTO_PRINTER = JsonFormat.printer();
	private static final Encoder RUNTIME_ENCODER = BuffJSON.encoder().withGeneratedEncoders(false);

	private BenchRepeatedHeavy[] randomRepeated;
	private BenchMapHeavy[] randomMaps;
	private int index;

	@Setup
	public void setup() {
		randomRepeated = BenchmarkData.createRandomBenchRepeatedHeavy(new Random(42), POOL_SIZE);
		randomMaps = BenchmarkData.createRandomBenchMapHeavy(new Random(43), POOL_SIZE);
	}

	@Benchmark
	public String repeatedCompiled() {
		return BuffJSON.encode(randomRepeated[index++ & MASK]);
	}

	@Benchmark
	public String repeatedRuntime() {
		return RUNTIME_ENCODER.encode(randomRepeated[index++ & MASK]);
	}

	@Benchmark
	public String repeatedJsonFormat() throws Exception {
		return PROTO_PRINTER.print(randomRepeated[index++ & MASK]);
	}

	@Benchmark
	public String mapCompiled() {
		return BuffJSON.encode(randomMaps[index++ & MASK]);
	}

	@Benchmark
	public String mapRuntime() {
		return RUNTIME_ENCODER.encode(randomMaps[index++ & MASK]);
	}

	@Benchmark
	public String mapJsonFormat() throws Exception {
		return PROTO_PRINTER.print(randomMaps[index++ & MASK]);
	}
}
