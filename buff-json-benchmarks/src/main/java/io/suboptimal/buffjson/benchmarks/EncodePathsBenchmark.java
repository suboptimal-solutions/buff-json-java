package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonEncoder;
import io.suboptimal.buffjson.proto.BenchMapHeavy;
import io.suboptimal.buffjson.proto.BenchStruct;
import io.suboptimal.buffjson.proto.BenchTimestamps;
import io.suboptimal.buffjson.proto.ComplexMessage;
import io.suboptimal.buffjson.proto.SimpleMessage;

/**
 * Stable performance-regression matrix for every encoder implementation and
 * output encoding. Kept separate from the comparison suite so CI can run it
 * with a fixed set of benchmark identities.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class EncodePathsBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final BuffJsonEncoder CODEGEN = BuffJson.encoder();
	private static final BuffJsonEncoder TYPED = BuffJson.encoder().setGeneratedEncoders(false);
	private static final BuffJsonEncoder REFLECTION = BuffJson.encoder().setGeneratedEncoders(false)
			.setTypedAccessors(false);

	private SimpleMessage[] simpleMessages;
	private ComplexMessage[] complexMessages;
	private BenchMapHeavy[] mapMessages;
	private BenchStruct[] structMessages;
	private BenchTimestamps[] timestampMessages;
	private int mapIndex;
	private int structIndex;
	private int timestampIndex;
	private int simpleIndex;
	private int complexIndex;

	@Setup
	public void setup() {
		simpleMessages = BenchmarkData.createRandomSimpleMessages(new Random(42), POOL_SIZE);
		complexMessages = BenchmarkData.createRandomComplexMessages(new Random(43), POOL_SIZE);
		mapMessages = BenchmarkData.createRandomBenchMapHeavy(new Random(43), POOL_SIZE);
		structMessages = BenchmarkData.createRandomBenchStructs(new Random(45), POOL_SIZE);
		timestampMessages = BenchmarkData.createRandomBenchTimestamps(new Random(42), POOL_SIZE);
	}

	@Benchmark
	public String simpleCodegenUtf16() {
		return CODEGEN.encode(simpleMessages[simpleIndex++ & MASK]);
	}

	@Benchmark
	public byte[] simpleCodegenUtf8() {
		return CODEGEN.encodeToBytes(simpleMessages[simpleIndex++ & MASK]);
	}

	@Benchmark
	public String simpleTypedUtf16() {
		return TYPED.encode(simpleMessages[simpleIndex++ & MASK]);
	}

	@Benchmark
	public byte[] simpleTypedUtf8() {
		return TYPED.encodeToBytes(simpleMessages[simpleIndex++ & MASK]);
	}

	@Benchmark
	public String simpleReflectionUtf16() {
		return REFLECTION.encode(simpleMessages[simpleIndex++ & MASK]);
	}

	@Benchmark
	public byte[] simpleReflectionUtf8() {
		return REFLECTION.encodeToBytes(simpleMessages[simpleIndex++ & MASK]);
	}

	@Benchmark
	public String complexCodegenUtf16() {
		return CODEGEN.encode(complexMessages[complexIndex++ & MASK]);
	}

	@Benchmark
	public byte[] complexCodegenUtf8() {
		return CODEGEN.encodeToBytes(complexMessages[complexIndex++ & MASK]);
	}

	@Benchmark
	public String complexTypedUtf16() {
		return TYPED.encode(complexMessages[complexIndex++ & MASK]);
	}

	@Benchmark
	public byte[] complexTypedUtf8() {
		return TYPED.encodeToBytes(complexMessages[complexIndex++ & MASK]);
	}

	@Benchmark
	public String complexReflectionUtf16() {
		return REFLECTION.encode(complexMessages[complexIndex++ & MASK]);
	}

	@Benchmark
	public byte[] complexReflectionUtf8() {
		return REFLECTION.encodeToBytes(complexMessages[complexIndex++ & MASK]);
	}

	@Benchmark
	public String mapCodegenUtf16() {
		return CODEGEN.encode(mapMessages[mapIndex++ & MASK]);
	}

	@Benchmark
	public byte[] mapCodegenUtf8() {
		return CODEGEN.encodeToBytes(mapMessages[mapIndex++ & MASK]);
	}

	@Benchmark
	public String mapTypedUtf16() {
		return TYPED.encode(mapMessages[mapIndex++ & MASK]);
	}

	@Benchmark
	public byte[] mapTypedUtf8() {
		return TYPED.encodeToBytes(mapMessages[mapIndex++ & MASK]);
	}

	@Benchmark
	public String mapReflectionUtf16() {
		return REFLECTION.encode(mapMessages[mapIndex++ & MASK]);
	}

	@Benchmark
	public byte[] mapReflectionUtf8() {
		return REFLECTION.encodeToBytes(mapMessages[mapIndex++ & MASK]);
	}

	@Benchmark
	public String structCodegenUtf16() {
		return CODEGEN.encode(structMessages[structIndex++ & MASK]);
	}

	@Benchmark
	public byte[] structCodegenUtf8() {
		return CODEGEN.encodeToBytes(structMessages[structIndex++ & MASK]);
	}

	@Benchmark
	public String structTypedUtf16() {
		return TYPED.encode(structMessages[structIndex++ & MASK]);
	}

	@Benchmark
	public byte[] structTypedUtf8() {
		return TYPED.encodeToBytes(structMessages[structIndex++ & MASK]);
	}

	@Benchmark
	public String structReflectionUtf16() {
		return REFLECTION.encode(structMessages[structIndex++ & MASK]);
	}

	@Benchmark
	public byte[] structReflectionUtf8() {
		return REFLECTION.encodeToBytes(structMessages[structIndex++ & MASK]);
	}

	@Benchmark
	public String timestampCodegenUtf16() {
		return CODEGEN.encode(timestampMessages[timestampIndex++ & MASK]);
	}

	@Benchmark
	public byte[] timestampCodegenUtf8() {
		return CODEGEN.encodeToBytes(timestampMessages[timestampIndex++ & MASK]);
	}

	@Benchmark
	public String timestampTypedUtf16() {
		return TYPED.encode(timestampMessages[timestampIndex++ & MASK]);
	}

	@Benchmark
	public byte[] timestampTypedUtf8() {
		return TYPED.encodeToBytes(timestampMessages[timestampIndex++ & MASK]);
	}

	@Benchmark
	public String timestampReflectionUtf16() {
		return REFLECTION.encode(timestampMessages[timestampIndex++ & MASK]);
	}

	@Benchmark
	public byte[] timestampReflectionUtf8() {
		return REFLECTION.encodeToBytes(timestampMessages[timestampIndex++ & MASK]);
	}
}
