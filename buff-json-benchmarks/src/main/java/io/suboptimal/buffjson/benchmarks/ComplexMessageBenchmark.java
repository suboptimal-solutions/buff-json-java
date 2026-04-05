package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonEncoder;
import io.suboptimal.buffjson.proto.ComplexMessage;

/**
 * Core regression benchmark: nested messages, maps, repeated fields, oneof,
 * bytes, timestamps. Compares BuffJson vs JsonFormat.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Benchmark)
public class ComplexMessageBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final JsonFormat.Printer PROTO_PRINTER = JsonFormat.printer();
	private static final BuffJsonEncoder BUFF_JSON = BuffJson.encoder();
	private static final BuffJsonEncoder RUNTIME_ENCODER = BuffJson.encoder().setGeneratedEncoders(false);

	private ComplexMessage[] randomMessages;
	private int index;

	@Setup
	public void setup() {
		randomMessages = BenchmarkData.createRandomComplexMessages(new Random(42), POOL_SIZE);
	}

	@Benchmark
	public String buffJsonCompiled() {
		return BUFF_JSON.encode(randomMessages[index++ & MASK]);
	}

	@Benchmark
	public String buffJsonRuntime() {
		return RUNTIME_ENCODER.encode(randomMessages[index++ & MASK]);
	}

	@Benchmark
	public String protoJsonFormat() throws Exception {
		return PROTO_PRINTER.print(randomMessages[index++ & MASK]);
	}
}
