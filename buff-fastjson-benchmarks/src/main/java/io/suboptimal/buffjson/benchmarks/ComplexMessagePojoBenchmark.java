package io.suboptimal.buffjson.benchmarks;

import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson2.JSON;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.benchmarks.pojo.ComplexMessagePojo;
import io.suboptimal.buffjson.benchmarks.pojo.ComplexMessagePojoCompiled;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class ComplexMessagePojoBenchmark {

	private ComplexMessagePojo pojo;
	private ComplexMessagePojoCompiled pojoCompiled;

	@Setup
	public void setup() {
		pojo = BenchmarkData.createComplexMessagePojo();
		pojoCompiled = BenchmarkData.createComplexMessagePojoCompiled();
	}

	@Benchmark
	public String fastjson2Pojo() {
		return JSON.toJSONString(pojo);
	}

	@Benchmark
	public String fastjson2PojoCompiled() {
		return JSON.toJSONString(pojoCompiled);
	}
}
