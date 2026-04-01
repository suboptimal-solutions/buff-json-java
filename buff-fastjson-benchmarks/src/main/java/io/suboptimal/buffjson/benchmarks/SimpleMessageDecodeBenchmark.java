package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import com.hubspot.jackson.datatype.protobuf.ProtobufModule;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJSON;
import io.suboptimal.buffjson.Decoder;
import io.suboptimal.buffjson.proto.SimpleMessage;

/**
 * Deserialization benchmark: flat 6-field message (most common real-world
 * shape).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Benchmark)
public class SimpleMessageDecodeBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final JsonFormat.Parser PROTO_PARSER = JsonFormat.parser();
	private static final Decoder RUNTIME_DECODER = BuffJSON.decoder().withGeneratedDecoders(false);
	private static final ObjectMapper JACKSON_MAPPER = new ObjectMapper().registerModule(new ProtobufModule());

	private String[] jsonStrings;
	private int index;

	@Setup
	public void setup() throws Exception {
		SimpleMessage[] messages = BenchmarkData.createRandomSimpleMessages(new Random(42), POOL_SIZE);
		JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
		jsonStrings = new String[POOL_SIZE];
		for (int i = 0; i < POOL_SIZE; i++) {
			jsonStrings[i] = printer.print(messages[i]);
		}
	}

	@Benchmark
	public SimpleMessage buffJsonCompiled() {
		return BuffJSON.decode(jsonStrings[index++ & MASK], SimpleMessage.class);
	}

	@Benchmark
	public SimpleMessage buffJsonRuntime() {
		return RUNTIME_DECODER.decode(jsonStrings[index++ & MASK], SimpleMessage.class);
	}

	@Benchmark
	public SimpleMessage protoJsonFormat() throws Exception {
		SimpleMessage.Builder builder = SimpleMessage.newBuilder();
		PROTO_PARSER.merge(jsonStrings[index++ & MASK], builder);
		return builder.build();
	}

	@Benchmark
	public SimpleMessage jacksonProtobuf() throws Exception {
		return JACKSON_MAPPER.readValue(jsonStrings[index++ & MASK], SimpleMessage.class);
	}
}
