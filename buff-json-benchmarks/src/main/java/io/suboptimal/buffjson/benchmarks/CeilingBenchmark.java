package io.suboptimal.buffjson.benchmarks;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson2.JSON;

import org.openjdk.jmh.annotations.*;

import io.suboptimal.buffjson.BuffJson;
import io.suboptimal.buffjson.BuffJsonEncoder;
import io.suboptimal.buffjson.benchmarks.pojo.AddressPojo;
import io.suboptimal.buffjson.benchmarks.pojo.AddressPojoCompiled;
import io.suboptimal.buffjson.benchmarks.pojo.ComplexMessagePojo;
import io.suboptimal.buffjson.benchmarks.pojo.ComplexMessagePojoCompiled;
import io.suboptimal.buffjson.benchmarks.pojo.TagPojo;
import io.suboptimal.buffjson.benchmarks.pojo.TagPojoCompiled;
import io.suboptimal.buffjson.proto.ComplexMessage;

/**
 * Ceiling benchmark: compares fastjson2 raw POJO serialization (the ceiling)
 * against BuffJson compiled/runtime on equivalent ComplexMessage data. Answers:
 * "how close is BuffJson to native fastjson2 POJO speed?"
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Benchmark)
public class CeilingBenchmark {

	private static final int POOL_SIZE = 1024;
	private static final int MASK = POOL_SIZE - 1;
	private static final BuffJsonEncoder BUFF_JSON = BuffJson.encoder();
	private static final BuffJsonEncoder RUNTIME_ENCODER = BuffJson.encoder().setGeneratedEncoders(false);

	private ComplexMessage[] protoMessages;
	private ComplexMessagePojo[] pojoMessages;
	private ComplexMessagePojoCompiled[] pojoCompiledMessages;
	private int index;

	@Setup
	public void setup() {
		Random rng = new Random(42);
		protoMessages = BenchmarkData.createRandomComplexMessages(rng, POOL_SIZE);

		// Build matching POJOs from the same seed for equivalent data
		rng = new Random(42);
		pojoMessages = createRandomComplexMessagePojos(rng, POOL_SIZE);

		rng = new Random(42);
		pojoCompiledMessages = createRandomComplexMessagePojosCompiled(rng, POOL_SIZE);
	}

	@Benchmark
	public String fastjson2Runtime() {
		return JSON.toJSONString(pojoMessages[index++ & MASK]);
	}

	@Benchmark
	public String fastjson2Compiled() {
		return JSON.toJSONString(pojoCompiledMessages[index++ & MASK]);
	}

	@Benchmark
	public String buffJsonCompiled() {
		return BUFF_JSON.encode(protoMessages[index++ & MASK]);
	}

	@Benchmark
	public String buffJsonRuntime() {
		return RUNTIME_ENCODER.encode(protoMessages[index++ & MASK]);
	}

	// ---- POJO random factory (mirrors BenchmarkData.createRandomComplexMessages)
	// ----

	private static final String[] STATUSES = {"STATUS_UNKNOWN", "STATUS_ACTIVE", "STATUS_INACTIVE"};

	private static ComplexMessagePojo[] createRandomComplexMessagePojos(Random rng, int n) {
		ComplexMessagePojo[] result = new ComplexMessagePojo[n];
		for (int i = 0; i < n; i++) {
			ComplexMessagePojo p = new ComplexMessagePojo();
			p.setId(randomAscii(rng, 5 + rng.nextInt(10)));
			p.setName(randomAscii(rng, 5 + rng.nextInt(20)));
			p.setVersion(rng.nextInt(100));
			p.setPrimaryAddress(randomAddressPojo(rng));

			int tagCount = 1 + rng.nextInt(5);
			List<String> tagsList = new ArrayList<>(tagCount);
			for (int j = 0; j < tagCount; j++) {
				tagsList.add(randomAscii(rng, 3 + rng.nextInt(10)));
			}
			p.setTagsList(tagsList);

			int addrCount = 1 + rng.nextInt(3);
			List<AddressPojo> addresses = new ArrayList<>(addrCount);
			for (int j = 0; j < addrCount; j++) {
				addresses.add(randomAddressPojo(rng));
			}
			p.setAddresses(addresses);

			List<TagPojo> tags = new ArrayList<>(1);
			TagPojo tag = new TagPojo();
			tag.setKey(randomAscii(rng, 3));
			tag.setValue(randomAscii(rng, 5));
			tags.add(tag);
			p.setTags(tags);

			Map<String, String> metadata = new HashMap<>();
			metadata.put(randomAscii(rng, 5), randomAscii(rng, 10));
			p.setMetadata(metadata);

			Map<Integer, AddressPojo> addressBook = new HashMap<>();
			addressBook.put(rng.nextInt(100), randomAddressPojo(rng));
			p.setAddressBook(addressBook);

			if (rng.nextBoolean()) {
				p.setEmail(randomAscii(rng, 8) + "@example.com");
			} else {
				// consume the same random bytes as the proto factory
				randomAscii(rng, 8);
			}

			byte[] payload = new byte[10 + rng.nextInt(50)];
			rng.nextBytes(payload);
			p.setPayload(Base64.getEncoder().encodeToString(payload));

			long sec1 = (long) (rng.nextDouble() * 253402300799L);
			int nanos1 = rng.nextInt(1000000000);
			p.setCreatedAt(formatTimestamp(sec1, nanos1));

			long sec2 = (long) (rng.nextDouble() * 253402300799L);
			int nanos2 = rng.nextInt(1000000000);
			p.setUpdatedAt(formatTimestamp(sec2, nanos2));

			p.setStatus(STATUSES[rng.nextInt(STATUSES.length - 1)]);

			result[i] = p;
		}
		return result;
	}

	private static AddressPojo randomAddressPojo(Random rng) {
		AddressPojo a = new AddressPojo();
		a.setStreet(rng.nextInt(9999) + " " + randomAscii(rng, 5) + " St");
		a.setCity(randomAscii(rng, 8));
		a.setState(randomAscii(rng, 2).toUpperCase());
		a.setZipCode(String.valueOf(10000 + rng.nextInt(90000)));
		a.setCountry("US");
		return a;
	}

	private static String randomAscii(Random rng, int len) {
		char[] chars = new char[len];
		for (int i = 0; i < len; i++) {
			chars[i] = (char) ('a' + rng.nextInt(26));
		}
		return new String(chars);
	}

	private static ComplexMessagePojoCompiled[] createRandomComplexMessagePojosCompiled(Random rng, int n) {
		ComplexMessagePojoCompiled[] result = new ComplexMessagePojoCompiled[n];
		for (int i = 0; i < n; i++) {
			ComplexMessagePojoCompiled p = new ComplexMessagePojoCompiled();
			p.setId(randomAscii(rng, 5 + rng.nextInt(10)));
			p.setName(randomAscii(rng, 5 + rng.nextInt(20)));
			p.setVersion(rng.nextInt(100));
			p.setPrimaryAddress(randomAddressPojoCompiled(rng));

			int tagCount = 1 + rng.nextInt(5);
			List<String> tagsList = new ArrayList<>(tagCount);
			for (int j = 0; j < tagCount; j++) {
				tagsList.add(randomAscii(rng, 3 + rng.nextInt(10)));
			}
			p.setTagsList(tagsList);

			int addrCount = 1 + rng.nextInt(3);
			List<AddressPojoCompiled> addresses = new ArrayList<>(addrCount);
			for (int j = 0; j < addrCount; j++) {
				addresses.add(randomAddressPojoCompiled(rng));
			}
			p.setAddresses(addresses);

			List<TagPojoCompiled> tags = new ArrayList<>(1);
			TagPojoCompiled tag = new TagPojoCompiled();
			tag.setKey(randomAscii(rng, 3));
			tag.setValue(randomAscii(rng, 5));
			tags.add(tag);
			p.setTags(tags);

			Map<String, String> metadata = new HashMap<>();
			metadata.put(randomAscii(rng, 5), randomAscii(rng, 10));
			p.setMetadata(metadata);

			Map<Integer, AddressPojoCompiled> addressBook = new HashMap<>();
			addressBook.put(rng.nextInt(100), randomAddressPojoCompiled(rng));
			p.setAddressBook(addressBook);

			if (rng.nextBoolean()) {
				p.setEmail(randomAscii(rng, 8) + "@example.com");
			} else {
				randomAscii(rng, 8);
			}

			byte[] payload = new byte[10 + rng.nextInt(50)];
			rng.nextBytes(payload);
			p.setPayload(Base64.getEncoder().encodeToString(payload));

			long sec1 = (long) (rng.nextDouble() * 253402300799L);
			int nanos1 = rng.nextInt(1000000000);
			p.setCreatedAt(formatTimestamp(sec1, nanos1));

			long sec2 = (long) (rng.nextDouble() * 253402300799L);
			int nanos2 = rng.nextInt(1000000000);
			p.setUpdatedAt(formatTimestamp(sec2, nanos2));

			p.setStatus(STATUSES[rng.nextInt(STATUSES.length - 1)]);

			result[i] = p;
		}
		return result;
	}

	private static AddressPojoCompiled randomAddressPojoCompiled(Random rng) {
		AddressPojoCompiled a = new AddressPojoCompiled();
		a.setStreet(rng.nextInt(9999) + " " + randomAscii(rng, 5) + " St");
		a.setCity(randomAscii(rng, 8));
		a.setState(randomAscii(rng, 2).toUpperCase());
		a.setZipCode(String.valueOf(10000 + rng.nextInt(90000)));
		a.setCountry("US");
		return a;
	}

	private static String formatTimestamp(long seconds, int nanos) {
		java.time.Instant instant = java.time.Instant.ofEpochSecond(seconds, nanos);
		return instant.toString();
	}
}
