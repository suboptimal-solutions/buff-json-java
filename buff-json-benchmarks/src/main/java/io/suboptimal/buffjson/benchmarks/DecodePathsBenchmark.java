package io.suboptimal.buffjson.benchmarks;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonDecoder;
import io.suboptimal.buffjson.proto.BenchAllScalars;

/**
 * Decode comparison across message shapes and String/UTF-8 input. Uses only
 * existing public APIs so the exact benchmark bytecode can run against a
 * pre-optimization jar as well as the candidate. Setup verifies every fixture.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class DecodePathsBenchmark {

	@Param({"simple", "scalars", "complex", "repeated", "maps", "timestamps", "struct", "deep", "any"})
	public String shape;

	@Param({"false", "true"})
	public boolean generated;

	private static final int POOL_SIZE = 1024;
	private BuffJsonDecoder decoder;
	private Class<? extends Message> messageClass;
	private String[] strings;
	private byte[][] bytes;
	private int index;

	@Setup
	public void setup() throws Exception {
		Random random = new Random(42);
		Message[] messages = switch (shape) {
			case "simple" -> BenchmarkData.createRandomSimpleMessages(random, POOL_SIZE);
			case "scalars" -> BenchmarkData.createRandomBenchAllScalars(random, POOL_SIZE);
			case "complex" -> BenchmarkData.createRandomComplexMessages(random, POOL_SIZE);
			case "repeated" -> BenchmarkData.createRandomBenchRepeatedHeavy(random, POOL_SIZE);
			case "maps" -> BenchmarkData.createRandomBenchMapHeavy(random, POOL_SIZE);
			case "timestamps" -> BenchmarkData.createRandomBenchTimestamps(random, POOL_SIZE);
			case "struct" -> BenchmarkData.createRandomBenchStructs(random, POOL_SIZE);
			case "deep" -> BenchmarkData.createRandomBenchDeepNesting(random, POOL_SIZE);
			case "any" -> BenchmarkData.createRandomBenchAnyWithScalars(random, POOL_SIZE);
			default -> throw new IllegalArgumentException("Unknown shape: " + shape);
		};
		var registry = TypeRegistry.newBuilder().add(BenchAllScalars.getDescriptor()).add(Timestamp.getDescriptor())
				.build();
		decoder = BuffJson.decoder().setGeneratedDecoders(generated).setTypeRegistry(registry);
		var printer = JsonFormat.printer().usingTypeRegistry(registry).omittingInsignificantWhitespace();
		messageClass = messages[0].getClass();
		strings = new String[POOL_SIZE];
		bytes = new byte[POOL_SIZE][];
		for (int i = 0; i < POOL_SIZE; i++) {
			strings[i] = printer.print(messages[i]);
			bytes[i] = strings[i].getBytes(StandardCharsets.UTF_8);
			if (!messages[i].equals(decoder.decode(strings[i], messageClass))
					|| !messages[i].equals(decoder.decode(bytes[i], messageClass))) {
				throw new IllegalStateException("Decode mismatch for " + shape + " at fixture " + i);
			}
		}
	}

	@Benchmark
	public Message string() {
		return decoder.decode(strings[index++ & (POOL_SIZE - 1)], messageClass);
	}

	@Benchmark
	public Message utf8() {
		return decoder.decode(bytes[index++ & (POOL_SIZE - 1)], messageClass);
	}
}
