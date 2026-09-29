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
 * Decode with the canonical-input fast path switched on and off, for the same
 * documents in three layouts a producer may emit: {@code compact} (Java
 * {@code JsonFormat}, no whitespace), {@code spaced} (a blank after every colon
 * and comma, like Go's {@code protojson} in some builds) and {@code pretty}
 * (multi-line, indented). Shapes holding well-known types the fast path does
 * not read ({@code struct}, {@code any}) measure the price of a bail-out: the
 * document is scanned up to the unsupported member and then decoded again by
 * the general decoder.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class DecodeFastPathBenchmark {

	@Param({"simple", "scalars", "complex", "repeated", "maps", "timestamps", "struct", "deep", "any", "strings"})
	public String shape;

	@Param({"true", "false"})
	public boolean fast;

	@Param({"compact", "spaced", "pretty"})
	public String layout;

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
			case "strings" -> BenchmarkData.createRandomBenchStringHeavy(random, POOL_SIZE);
			default -> throw new IllegalArgumentException("Unknown shape: " + shape);
		};
		var registry = TypeRegistry.newBuilder().add(BenchAllScalars.getDescriptor()).add(Timestamp.getDescriptor())
				.build();
		decoder = BuffJson.decoder().setFastPath(fast).setTypeRegistry(registry);
		var compact = JsonFormat.printer().usingTypeRegistry(registry).omittingInsignificantWhitespace();
		var pretty = JsonFormat.printer().usingTypeRegistry(registry);
		messageClass = messages[0].getClass();
		strings = new String[POOL_SIZE];
		bytes = new byte[POOL_SIZE][];
		for (int i = 0; i < POOL_SIZE; i++) {
			strings[i] = switch (layout) {
				case "compact" -> compact.print(messages[i]);
				case "spaced" -> spaced(compact.print(messages[i]));
				case "pretty" -> pretty.print(messages[i]);
				default -> throw new IllegalArgumentException("Unknown layout: " + layout);
			};
			bytes[i] = strings[i].getBytes(StandardCharsets.UTF_8);
			if (!messages[i].equals(decoder.decode(strings[i], messageClass))
					|| !messages[i].equals(decoder.decode(bytes[i], messageClass))) {
				throw new IllegalStateException("Decode mismatch for " + shape + " at fixture " + i);
			}
		}
	}

	/** One blank after every structural colon and comma. */
	static String spaced(String compact) {
		StringBuilder out = new StringBuilder(compact.length() + compact.length() / 5);
		boolean inString = false;
		for (int i = 0; i < compact.length(); i++) {
			char c = compact.charAt(i);
			out.append(c);
			if (inString) {
				if (c == '\\') {
					out.append(compact.charAt(++i));
				} else if (c == '"') {
					inString = false;
				}
			} else if (c == '"') {
				inString = true;
			} else if (c == ':' || c == ',') {
				out.append(' ');
			}
		}
		return out.toString();
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
