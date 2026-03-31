package io.suboptimal.buffjson.benchmarks;

import java.util.Random;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;

import io.suboptimal.buffjson.proto.*;

public final class BenchmarkData {

	private BenchmarkData() {
	}

	// ---- Random factories ----

	private static final int BENCH_ENUM_COUNT = BenchEnum.values().length - 1; // exclude UNRECOGNIZED
	private static final int STATUS_COUNT = Status.values().length - 1; // exclude UNRECOGNIZED

	public static SimpleMessage[] createRandomSimpleMessages(Random rng, int n) {
		SimpleMessage[] result = new SimpleMessage[n];
		for (int i = 0; i < n; i++) {
			SimpleMessage.Builder b = SimpleMessage.newBuilder();
			if (rng.nextBoolean())
				b.setName(randomAscii(rng, 5 + rng.nextInt(20)));
			if (rng.nextBoolean())
				b.setId(rng.nextInt());
			if (rng.nextBoolean())
				b.setTimestampMillis(rng.nextLong());
			if (rng.nextBoolean())
				b.setScore(rng.nextDouble() * 1000);
			if (rng.nextBoolean())
				b.setActive(rng.nextBoolean());
			if (rng.nextBoolean())
				b.setStatus(Status.forNumber(rng.nextInt(STATUS_COUNT)));
			result[i] = b.build();
		}
		return result;
	}

	public static ComplexMessage[] createRandomComplexMessages(Random rng, int n) {
		ComplexMessage[] result = new ComplexMessage[n];
		for (int i = 0; i < n; i++) {
			ComplexMessage.Builder b = ComplexMessage.newBuilder();
			b.setId(randomAscii(rng, 5 + rng.nextInt(10)));
			b.setName(randomAscii(rng, 5 + rng.nextInt(20)));
			b.setVersion(rng.nextInt(100));
			b.setPrimaryAddress(randomAddress(rng));
			int tagCount = 1 + rng.nextInt(5);
			for (int j = 0; j < tagCount; j++) {
				b.addTagsList(randomAscii(rng, 3 + rng.nextInt(10)));
			}
			int addrCount = 1 + rng.nextInt(3);
			for (int j = 0; j < addrCount; j++) {
				b.addAddresses(randomAddress(rng));
			}
			b.addTags(Tag.newBuilder().setKey(randomAscii(rng, 3)).setValue(randomAscii(rng, 5)).build());
			b.putMetadata(randomAscii(rng, 5), randomAscii(rng, 10));
			b.putAddressBook(rng.nextInt(100), randomAddress(rng));
			if (rng.nextBoolean()) {
				b.setEmail(randomAscii(rng, 8) + "@example.com");
			} else {
				b.setPhone("+1" + (1000000000L + rng.nextInt(900000000)));
			}
			b.setPayload(randomBytes(rng, 10 + rng.nextInt(50)));
			b.setCreatedAt(randomTimestamp(rng));
			b.setUpdatedAt(randomTimestamp(rng));
			b.setStatus(Status.forNumber(rng.nextInt(STATUS_COUNT)));
			result[i] = b.build();
		}
		return result;
	}

	public static BenchAllScalars[] createRandomBenchAllScalars(Random rng, int n) {
		BenchAllScalars[] result = new BenchAllScalars[n];
		for (int i = 0; i < n; i++) {
			BenchAllScalars.Builder b = BenchAllScalars.newBuilder();
			if (rng.nextBoolean())
				b.setFInt32(rng.nextInt());
			if (rng.nextBoolean())
				b.setFInt64(rng.nextLong());
			if (rng.nextBoolean())
				b.setFUint32(rng.nextInt());
			if (rng.nextBoolean())
				b.setFUint64(rng.nextLong());
			if (rng.nextBoolean())
				b.setFSint32(rng.nextInt());
			if (rng.nextBoolean())
				b.setFSint64(rng.nextLong());
			if (rng.nextBoolean())
				b.setFFixed32(rng.nextInt());
			if (rng.nextBoolean())
				b.setFFixed64(rng.nextLong());
			if (rng.nextBoolean())
				b.setFSfixed32(rng.nextInt());
			if (rng.nextBoolean())
				b.setFSfixed64(rng.nextLong());
			if (rng.nextBoolean())
				b.setFFloat(rng.nextFloat() * 1000 - 500);
			if (rng.nextBoolean())
				b.setFDouble(rng.nextDouble() * 1e10 - 5e9);
			if (rng.nextBoolean())
				b.setFBool(true);
			if (rng.nextBoolean())
				b.setFString(randomAscii(rng, 5 + rng.nextInt(20)));
			if (rng.nextBoolean())
				b.setFBytes(randomBytes(rng, 4 + rng.nextInt(32)));
			if (rng.nextBoolean())
				b.setFEnum(BenchEnum.forNumber(rng.nextInt(BENCH_ENUM_COUNT)));
			result[i] = b.build();
		}
		return result;
	}

	public static BenchTimestamps[] createRandomBenchTimestamps(Random rng, int n) {
		BenchTimestamps[] result = new BenchTimestamps[n];
		for (int i = 0; i < n; i++) {
			result[i] = BenchTimestamps.newBuilder().setTsSecondsOnly(randomTimestamp(rng, 0))
					.setTsMillis(randomTimestamp(rng, 3)).setTsNanos(randomTimestamp(rng, 9)).build();
		}
		return result;
	}

	public static BenchStruct[] createRandomBenchStructs(Random rng, int n) {
		BenchStruct[] result = new BenchStruct[n];
		for (int i = 0; i < n; i++) {
			result[i] = BenchStruct.newBuilder().setData(randomStruct(rng, 3 + rng.nextInt(13), 2)).build();
		}
		return result;
	}

	public static BenchRepeatedHeavy[] createRandomBenchRepeatedHeavy(Random rng, int n) {
		BenchRepeatedHeavy[] result = new BenchRepeatedHeavy[n];
		for (int i = 0; i < n; i++) {
			BenchRepeatedHeavy.Builder b = BenchRepeatedHeavy.newBuilder();
			int intCount = 50 + rng.nextInt(151);
			for (int j = 0; j < intCount; j++) {
				b.addInts(rng.nextInt());
			}
			int strCount = 50 + rng.nextInt(151);
			for (int j = 0; j < strCount; j++) {
				b.addStrings(randomAscii(rng, 3 + rng.nextInt(15)));
			}
			int msgCount = 10 + rng.nextInt(21);
			for (int j = 0; j < msgCount; j++) {
				b.addMessages(BenchAllScalars.newBuilder().setFInt32(rng.nextInt()).setFString(randomAscii(rng, 5))
						.setFBool(rng.nextBoolean()).setFDouble(rng.nextDouble() * 100).build());
			}
			result[i] = b.build();
		}
		return result;
	}

	public static BenchMapHeavy[] createRandomBenchMapHeavy(Random rng, int n) {
		BenchMapHeavy[] result = new BenchMapHeavy[n];
		for (int i = 0; i < n; i++) {
			BenchMapHeavy.Builder b = BenchMapHeavy.newBuilder();
			int strMapSize = 20 + rng.nextInt(61);
			for (int j = 0; j < strMapSize; j++) {
				b.putStringMap(randomAscii(rng, 5 + rng.nextInt(10)), randomAscii(rng, 5 + rng.nextInt(20)));
			}
			int intMapSize = 20 + rng.nextInt(61);
			for (int j = 0; j < intMapSize; j++) {
				b.putIntKeyMap(rng.nextLong(), randomAscii(rng, 5 + rng.nextInt(15)));
			}
			int msgMapSize = 10 + rng.nextInt(21);
			for (int j = 0; j < msgMapSize; j++) {
				b.putMessageMap(randomAscii(rng, 5),
						BenchAllScalars.newBuilder().setFInt32(rng.nextInt()).setFString(randomAscii(rng, 5)).build());
			}
			result[i] = b.build();
		}
		return result;
	}

	public static BenchDeepNesting[] createRandomBenchDeepNesting(Random rng, int n) {
		BenchDeepNesting[] result = new BenchDeepNesting[n];
		for (int i = 0; i < n; i++) {
			int depth = 3 + rng.nextInt(5);
			BenchDeepNesting current = BenchDeepNesting.newBuilder().setName(randomAscii(rng, 5))
					.setValue(rng.nextInt(1000)).build();
			for (int d = depth - 1; d >= 1; d--) {
				current = BenchDeepNesting.newBuilder().setName(randomAscii(rng, 3 + rng.nextInt(8)))
						.setValue(rng.nextInt(1000)).setChild(current).build();
			}
			result[i] = current;
		}
		return result;
	}

	public static BenchStringHeavy[] createRandomBenchStringHeavy(Random rng, int n) {
		BenchStringHeavy[] result = new BenchStringHeavy[n];
		for (int i = 0; i < n; i++) {
			int longLen = 100 + rng.nextInt(1901);
			StringBuilder longStr = new StringBuilder(longLen);
			for (int j = 0; j < longLen; j++) {
				longStr.append((char) ('a' + rng.nextInt(26)));
			}
			int escapeLen = 50 + rng.nextInt(201);
			StringBuilder escapeStr = new StringBuilder(escapeLen * 4);
			for (int j = 0; j < escapeLen; j++) {
				switch (rng.nextInt(5)) {
					case 0 -> escapeStr.append('\n');
					case 1 -> escapeStr.append('\t');
					case 2 -> escapeStr.append('"');
					case 3 -> escapeStr.append('\\');
					default -> escapeStr.append(randomAscii(rng, 3));
				}
			}
			String[] unicodeFragments = {"\u4f60\u597d", "\u4e16\u754c", "\ud83d\ude80", "\ud83c\udf1f",
					"\u00e9\u00e8\u00ea", "\u03b1\u03b2\u03b3", "\u0410\u0411\u0412", "\u2603", "\u2764",
					"\ud83c\udf89"};
			StringBuilder unicode = new StringBuilder();
			int uniCount = 5 + rng.nextInt(16);
			for (int j = 0; j < uniCount; j++) {
				unicode.append(unicodeFragments[rng.nextInt(unicodeFragments.length)]);
				unicode.append(' ');
			}
			result[i] = BenchStringHeavy.newBuilder().setShortAscii(randomAscii(rng, 3 + rng.nextInt(15)))
					.setLongAscii(longStr.toString()).setUnicodeText(unicode.toString())
					.setEscapeHeavy(escapeStr.toString()).setSmallPayload(randomBytes(rng, 8 + rng.nextInt(24)))
					.setLargePayload(randomBytes(rng, 1024 + rng.nextInt(4096))).build();
		}
		return result;
	}

	public static BenchAny[] createRandomBenchAnyWithScalars(Random rng, int n) {
		BenchAllScalars[] scalars = createRandomBenchAllScalars(rng, n);
		BenchAny[] result = new BenchAny[n];
		for (int i = 0; i < n; i++) {
			result[i] = BenchAny.newBuilder().setValue(Any.pack(scalars[i])).build();
		}
		return result;
	}

	public static BenchAny[] createRandomBenchAnyWithTimestamp(Random rng, int n) {
		BenchAny[] result = new BenchAny[n];
		for (int i = 0; i < n; i++) {
			Timestamp ts = randomTimestamp(rng);
			result[i] = BenchAny.newBuilder().setValue(Any.pack(ts)).build();
		}
		return result;
	}

	// ---- Helpers ----

	static String randomAscii(Random rng, int len) {
		char[] chars = new char[len];
		for (int i = 0; i < len; i++) {
			chars[i] = (char) ('a' + rng.nextInt(26));
		}
		return new String(chars);
	}

	private static ByteString randomBytes(Random rng, int len) {
		byte[] bytes = new byte[len];
		rng.nextBytes(bytes);
		return ByteString.copyFrom(bytes);
	}

	private static Timestamp randomTimestamp(Random rng) {
		long seconds = (long) (rng.nextDouble() * 253402300799L);
		int nanos = rng.nextInt(1000000000);
		return Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
	}

	private static Timestamp randomTimestamp(Random rng, int nanoPrecisionDigits) {
		long seconds = (long) (rng.nextDouble() * 253402300799L);
		int nanos;
		if (nanoPrecisionDigits == 0) {
			nanos = 0;
		} else if (nanoPrecisionDigits == 3) {
			nanos = rng.nextInt(1000) * 1000000;
		} else {
			nanos = rng.nextInt(1000000000);
		}
		return Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
	}

	private static Address randomAddress(Random rng) {
		return Address.newBuilder().setStreet(rng.nextInt(9999) + " " + randomAscii(rng, 5) + " St")
				.setCity(randomAscii(rng, 8)).setState(randomAscii(rng, 2).toUpperCase())
				.setZipCode(String.valueOf(10000 + rng.nextInt(90000))).setCountry("US").build();
	}

	private static Struct randomStruct(Random rng, int fieldCount, int maxDepth) {
		Struct.Builder sb = Struct.newBuilder();
		for (int i = 0; i < fieldCount; i++) {
			sb.putFields("f_" + i, randomValue(rng, maxDepth));
		}
		return sb.build();
	}

	private static Value randomValue(Random rng, int maxDepth) {
		int kind = (maxDepth > 0) ? rng.nextInt(6) : rng.nextInt(4);
		return switch (kind) {
			case 0 -> Value.newBuilder().setStringValue(randomAscii(rng, 3 + rng.nextInt(15))).build();
			case 1 -> Value.newBuilder().setNumberValue(rng.nextDouble() * 1000 - 500).build();
			case 2 -> Value.newBuilder().setBoolValue(rng.nextBoolean()).build();
			case 3 -> Value.newBuilder().setNullValueValue(0).build();
			case 4 -> Value.newBuilder().setStructValue(randomStruct(rng, 2 + rng.nextInt(4), maxDepth - 1)).build();
			default -> {
				ListValue.Builder lb = ListValue.newBuilder();
				int listSize = 1 + rng.nextInt(4);
				for (int j = 0; j < listSize; j++) {
					lb.addValues(randomValue(rng, maxDepth - 1));
				}
				yield Value.newBuilder().setListValue(lb).build();
			}
		};
	}
}
