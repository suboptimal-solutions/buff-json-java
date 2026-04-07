package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonEncoder;
import io.suboptimal.buffjson.proto.SimpleMessage;

/**
 * Core regression benchmark: flat 6-field message (most common real-world
 * shape). Compares BuffJson vs JsonFormat.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Benchmark)
public class SimpleMessageBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final JsonFormat.Printer PROTO_PRINTER = JsonFormat.printer();
	private static final BuffJsonEncoder BUFF_JSON = BuffJson.encoder();
	private static final BuffJsonEncoder RUNTIME_ENCODER = BuffJson.encoder().setGeneratedEncoders(false);

	private SimpleMessage[] randomMessages;
	private int index;

	@Setup
	public void setup() {
		randomMessages = BenchmarkData.createRandomSimpleMessages(new Random(42), POOL_SIZE);
	}

	@Benchmark
	public String compiledUtf16() {
		return BUFF_JSON.encode(randomMessages[index++ & MASK]);
	}

	@Benchmark
	public void compiledUtf8(org.openjdk.jmh.infra.Blackhole bh) {
		bh.consume(BUFF_JSON.encodeToBytes(randomMessages[index++ & MASK]));
	}

	@Benchmark
	public String runtimeUtf16() {
		return RUNTIME_ENCODER.encode(randomMessages[index++ & MASK]);
	}

	@Benchmark
	public void runtimeUtf8(org.openjdk.jmh.infra.Blackhole bh) {
		bh.consume(RUNTIME_ENCODER.encodeToBytes(randomMessages[index++ & MASK]));
	}

	@Benchmark
	public String protoJsonFormat() throws Exception {
		return PROTO_PRINTER.print(randomMessages[index++ & MASK]);
	}
}
