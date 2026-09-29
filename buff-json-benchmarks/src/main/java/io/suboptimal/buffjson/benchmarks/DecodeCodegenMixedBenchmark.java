package io.suboptimal.buffjson.benchmarks;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonDecoder;
import io.suboptimal.buffjson.internal.codegen.RuntimeCodegen;

/**
 * {@link DecodeCodegenBenchmark} with a rotating mix of six message types
 * through one decoder, so the generated readers are reached through a
 * megamorphic call site, as in a service that handles several types. The
 * single-shape benchmarks let the JIT see one class at every call site; this is
 * the check that the run-time generated readers keep their advantage without
 * that favourable profile.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class DecodeCodegenMixedBenchmark {

	/** See {@link DecodeCodegenBenchmark#mode}. */
	@Param({"typed", "runtime", "plugin"})
	public String mode;

	private static final int POOL_SIZE = 256;
	private BuffJsonDecoder decoder;
	private Class<? extends Message>[] classes;
	private byte[][][] documents; // [type][i]
	private int index;

	@SuppressWarnings("unchecked")
	@Setup
	public void setup() {
		Random random = new Random(42);
		Message[][] messages = {BenchmarkData.createRandomSimpleMessages(random, POOL_SIZE),
				BenchmarkData.createRandomBenchAllScalars(random, POOL_SIZE),
				BenchmarkData.createRandomBenchTimestamps(random, POOL_SIZE),
				BenchmarkData.createRandomBenchDeepNesting(random, POOL_SIZE),
				BenchmarkData.createRandomComplexMessages(random, POOL_SIZE),
				BenchmarkData.createRandomBenchMapHeavy(random, POOL_SIZE)};
		decoder = switch (mode) {
			case "typed" -> BuffJson.decoder().setGeneratedDecoders(false).setRuntimeCodegen(false);
			case "runtime" -> BuffJson.decoder().setGeneratedDecoders(false).setRuntimeCodegen(true);
			case "plugin" -> BuffJson.decoder().setRuntimeCodegen(false);
			default -> throw new IllegalArgumentException("Unknown mode: " + mode);
		};
		var printer = JsonFormat.printer().omittingInsignificantWhitespace();
		classes = new Class[messages.length];
		documents = new byte[messages.length][POOL_SIZE][];
		for (int t = 0; t < messages.length; t++) {
			classes[t] = messages[t][0].getClass();
			if (mode.equals("runtime") && RuntimeCodegen.fastDecoder(classes[t], false) == null) {
				throw new IllegalStateException("no run-time generated decoder for " + classes[t].getName());
			}
			for (int i = 0; i < POOL_SIZE; i++) {
				try {
					documents[t][i] = printer.print(messages[t][i]).getBytes(StandardCharsets.UTF_8);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
				if (!messages[t][i].equals(decoder.decode(documents[t][i], classes[t]))) {
					throw new IllegalStateException("Decode mismatch for type " + t + " at fixture " + i);
				}
			}
		}
	}

	@Benchmark
	public Message utf8() {
		int n = index++;
		int type = n % classes.length;
		return decoder.decode(documents[type][(n / classes.length) & (POOL_SIZE - 1)], classes[type]);
	}
}
