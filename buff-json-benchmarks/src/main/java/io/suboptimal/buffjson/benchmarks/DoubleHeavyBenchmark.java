package io.suboptimal.buffjson.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson2.JSON;
import com.google.protobuf.util.JsonFormat;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonEncoder;
import io.suboptimal.buffjson.proto.BenchDoubleHeavy;

/**
 * Benchmark for messages with 25 double fields — represents IoT/telemetry
 * workloads (sensor readings, GPS coordinates, weather data).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Benchmark)
public class DoubleHeavyBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final JsonFormat.Printer PROTO_PRINTER = JsonFormat.printer();
	private static final BuffJsonEncoder BUFF_JSON = BuffJson.encoder();
	private static final BuffJsonEncoder RUNTIME_ENCODER = BuffJson.encoder().setGeneratedEncoders(false);

	private BenchDoubleHeavy[] protoMessages;
	private DoubleHeavyPojo[] pojoMessages;
	private int index;

	@Setup
	public void setup() {
		Random rng = new Random(42);
		protoMessages = BenchmarkData.createRandomBenchDoubleHeavy(rng, POOL_SIZE);

		rng = new Random(42);
		pojoMessages = new DoubleHeavyPojo[POOL_SIZE];
		for (int i = 0; i < POOL_SIZE; i++) {
			pojoMessages[i] = DoubleHeavyPojo.random(rng);
		}
	}

	@Benchmark
	public String compiledUtf16() {
		return BUFF_JSON.encode(protoMessages[index++ & MASK]);
	}

	@Benchmark
	public void compiledUtf8(org.openjdk.jmh.infra.Blackhole bh) {
		bh.consume(BUFF_JSON.encodeToBytes(protoMessages[index++ & MASK]));
	}

	@Benchmark
	public String runtimeUtf16() {
		return RUNTIME_ENCODER.encode(protoMessages[index++ & MASK]);
	}

	@Benchmark
	public void runtimeUtf8(org.openjdk.jmh.infra.Blackhole bh) {
		bh.consume(RUNTIME_ENCODER.encodeToBytes(protoMessages[index++ & MASK]));
	}

	@Benchmark
	public String fastjson2PojoUtf16() {
		return JSON.toJSONString(pojoMessages[index++ & MASK]);
	}

	@Benchmark
	public void fastjson2PojoUtf8(org.openjdk.jmh.infra.Blackhole bh) {
		bh.consume(JSON.toJSONBytes(pojoMessages[index++ & MASK]));
	}

	@Benchmark
	public String protoJsonFormat() throws Exception {
		return PROTO_PRINTER.print(protoMessages[index++ & MASK]);
	}

	/** Plain POJO with 25 double fields — fastjson2 ceiling. */
	public static class DoubleHeavyPojo {
		public double lat;
		public double lng;
		public double altitude;
		public double speed;
		public double heading;
		public double accuracy;
		public double temperature;
		public double pressure;
		public double humidity;
		public double windSpeed;
		public double windDirection;
		public double visibility;
		public double uvIndex;
		public double dewPoint;
		public double feelsLike;
		public double precipitation;
		public double cloudCover;
		public double solarRadiation;
		public double soilTemperature;
		public double soilMoisture;
		public double voltage;
		public double current;
		public double power;
		public double frequency;
		public double signalStrength;

		static DoubleHeavyPojo random(Random rng) {
			DoubleHeavyPojo p = new DoubleHeavyPojo();
			p.lat = 37.0 + rng.nextDouble();
			p.lng = -122.0 + rng.nextDouble();
			p.altitude = rng.nextDouble() * 5000;
			p.speed = rng.nextDouble() * 200;
			p.heading = rng.nextDouble() * 360;
			p.accuracy = rng.nextDouble() * 50;
			p.temperature = -40 + rng.nextDouble() * 100;
			p.pressure = 900 + rng.nextDouble() * 200;
			p.humidity = rng.nextDouble() * 100;
			p.windSpeed = rng.nextDouble() * 150;
			p.windDirection = rng.nextDouble() * 360;
			p.visibility = rng.nextDouble() * 50000;
			p.uvIndex = rng.nextDouble() * 15;
			p.dewPoint = -20 + rng.nextDouble() * 60;
			p.feelsLike = -40 + rng.nextDouble() * 100;
			p.precipitation = rng.nextDouble() * 500;
			p.cloudCover = rng.nextDouble() * 100;
			p.solarRadiation = rng.nextDouble() * 1500;
			p.soilTemperature = -10 + rng.nextDouble() * 60;
			p.soilMoisture = rng.nextDouble() * 100;
			p.voltage = rng.nextDouble() * 480;
			p.current = rng.nextDouble() * 1000;
			p.power = rng.nextDouble() * 100000;
			p.frequency = 49.5 + rng.nextDouble();
			p.signalStrength = -120 + rng.nextDouble() * 80;
			return p;
		}
	}
}
