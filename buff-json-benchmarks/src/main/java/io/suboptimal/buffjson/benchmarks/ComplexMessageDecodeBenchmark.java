package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonDecoder;
import io.suboptimal.buffjson.proto.ComplexMessage;

/**
 * Deserialization benchmark: nested messages, maps, repeated fields, oneof,
 * bytes, timestamps. Compares BuffJson vs JsonFormat.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Benchmark)
public class ComplexMessageDecodeBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final JsonFormat.Parser PROTO_PARSER = JsonFormat.parser();
	private static final BuffJsonDecoder RUNTIME_DECODER = BuffJson.decoder().withGeneratedDecoders(false);

	private String[] jsonStrings;
	private int index;

	@Setup
	public void setup() throws Exception {
		ComplexMessage[] messages = BenchmarkData.createRandomComplexMessages(new Random(42), POOL_SIZE);
		JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
		jsonStrings = new String[POOL_SIZE];
		for (int i = 0; i < POOL_SIZE; i++) {
			jsonStrings[i] = printer.print(messages[i]);
		}
	}

	@Benchmark
	public ComplexMessage buffJsonCompiled() {
		return BuffJson.decode(jsonStrings[index++ & MASK], ComplexMessage.class);
	}

	@Benchmark
	public ComplexMessage buffJsonRuntime() {
		return RUNTIME_DECODER.decode(jsonStrings[index++ & MASK], ComplexMessage.class);
	}

	@Benchmark
	public ComplexMessage protoJsonFormat() throws Exception {
		ComplexMessage.Builder builder = ComplexMessage.newBuilder();
		PROTO_PARSER.merge(jsonStrings[index++ & MASK], builder);
		return builder.build();
	}
}
