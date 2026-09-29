package io.suboptimal.buffjson.benchmarks;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonDecoder;

/**
 * Decodes a rotating mix of six different message types with one decoder, with
 * the canonical-input fast path off and on. The single-shape benchmarks let the
 * JIT see one message class at every call site; here the generated decoders are
 * reached through a megamorphic interface call, as in a service that handles
 * several types, so this is the check that the gains do not depend on that
 * favourable profile.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 4, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class DecodeMixedBenchmark {

	@Param({"true", "false"})
	public boolean fast;

	private static final int POOL_SIZE = 256;
	private BuffJsonDecoder decoder;
	private Class<? extends Message>[] classes;
	private byte[][][] documents; // [type][i]
	private String[][] strings;
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
		decoder = BuffJson.decoder().setFastPath(fast);
		var printer = JsonFormat.printer().omittingInsignificantWhitespace();
		classes = new Class[messages.length];
		documents = new byte[messages.length][POOL_SIZE][];
		strings = new String[messages.length][POOL_SIZE];
		for (int t = 0; t < messages.length; t++) {
			classes[t] = messages[t][0].getClass();
			for (int i = 0; i < POOL_SIZE; i++) {
				try {
					strings[t][i] = printer.print(messages[t][i]);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
				documents[t][i] = strings[t][i].getBytes(StandardCharsets.UTF_8);
				if (!messages[t][i].equals(decoder.decode(documents[t][i], classes[t]))
						|| !messages[t][i].equals(decoder.decode(strings[t][i], classes[t]))) {
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

	@Benchmark
	public Message string() {
		int n = index++;
		int type = n % classes.length;
		return decoder.decode(strings[type][(n / classes.length) & (POOL_SIZE - 1)], classes[type]);
	}
}
