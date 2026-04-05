package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.protobuf.util.JsonFormat;
import com.hubspot.jackson.datatype.protobuf.ProtobufModule;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.jackson.ProtobufJacksonModule;
import io.suboptimal.buffjson.proto.ComplexMessage;
import io.suboptimal.buffjson.proto.SimpleMessage;

/**
 * Jackson comparison benchmark: compares HubSpot jackson-datatype-protobuf and
 * BuffJson's Jackson module against BuffJson compiled on Simple and Complex
 * messages (encode + decode). Answers: "how does BuffJson compare to Jackson
 * for protobuf JSON?"
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Benchmark)
public class JacksonBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;

	private static final ObjectMapper JACKSON_MAPPER = new ObjectMapper().registerModule(new ProtobufModule());
	private static final ObjectMapper BUFF_JACKSON_MAPPER = JsonMapper.builder()
			.enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).addModule(new ProtobufJacksonModule()).build();

	private SimpleMessage[] simpleMessages;
	private ComplexMessage[] complexMessages;
	private String[] simpleJsonStrings;
	private String[] complexJsonStrings;
	private int index;

	@Setup
	public void setup() throws Exception {
		Random rng = new Random(42);
		simpleMessages = BenchmarkData.createRandomSimpleMessages(rng, POOL_SIZE);

		rng = new Random(42);
		complexMessages = BenchmarkData.createRandomComplexMessages(rng, POOL_SIZE);

		JsonFormat.Printer printer = JsonFormat.printer().omittingInsignificantWhitespace();
		simpleJsonStrings = new String[POOL_SIZE];
		complexJsonStrings = new String[POOL_SIZE];
		for (int i = 0; i < POOL_SIZE; i++) {
			simpleJsonStrings[i] = printer.print(simpleMessages[i]);
			complexJsonStrings[i] = printer.print(complexMessages[i]);
		}
	}

	// ---- Simple encode ----

	@Benchmark
	public String simpleEncodeBuffJson() {
		return BuffJson.encode(simpleMessages[index++ & MASK]);
	}

	@Benchmark
	public String simpleEncodeJackson() throws Exception {
		return JACKSON_MAPPER.writeValueAsString(simpleMessages[index++ & MASK]);
	}

	@Benchmark
	public String simpleEncodeBuffJsonJackson() throws Exception {
		return BUFF_JACKSON_MAPPER.writeValueAsString(simpleMessages[index++ & MASK]);
	}

	// ---- Complex encode ----

	@Benchmark
	public String complexEncodeBuffJson() {
		return BuffJson.encode(complexMessages[index++ & MASK]);
	}

	@Benchmark
	public String complexEncodeJackson() throws Exception {
		return JACKSON_MAPPER.writeValueAsString(complexMessages[index++ & MASK]);
	}

	@Benchmark
	public String complexEncodeBuffJsonJackson() throws Exception {
		return BUFF_JACKSON_MAPPER.writeValueAsString(complexMessages[index++ & MASK]);
	}

	// ---- Simple decode ----

	@Benchmark
	public SimpleMessage simpleDecodeBuffJson() {
		return BuffJson.decode(simpleJsonStrings[index++ & MASK], SimpleMessage.class);
	}

	@Benchmark
	public SimpleMessage simpleDecodeJackson() throws Exception {
		return JACKSON_MAPPER.readValue(simpleJsonStrings[index++ & MASK], SimpleMessage.class);
	}

	@Benchmark
	public SimpleMessage simpleDecodeBuffJsonJackson() throws Exception {
		return BUFF_JACKSON_MAPPER.readValue(simpleJsonStrings[index++ & MASK], SimpleMessage.class);
	}

	// ---- Complex decode ----

	@Benchmark
	public ComplexMessage complexDecodeBuffJson() {
		return BuffJson.decode(complexJsonStrings[index++ & MASK], ComplexMessage.class);
	}

	@Benchmark
	public ComplexMessage complexDecodeJackson() throws Exception {
		return JACKSON_MAPPER.readValue(complexJsonStrings[index++ & MASK], ComplexMessage.class);
	}

	@Benchmark
	public ComplexMessage complexDecodeBuffJsonJackson() throws Exception {
		return BUFF_JACKSON_MAPPER.readValue(complexJsonStrings[index++ & MASK], ComplexMessage.class);
	}
}
