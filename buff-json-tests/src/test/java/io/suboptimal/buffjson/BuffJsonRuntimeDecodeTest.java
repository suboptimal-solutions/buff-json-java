package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONReader;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf_test_messages.proto3.TestMessagesProto3.TestAllTypesProto3;
import com.google.type.Money;

import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.internal.ProtobufMessageReader;
import io.suboptimal.buffjson.internal.TypedMessageReaderSchema;
import io.suboptimal.buffjson.proto.*;

class BuffJsonRuntimeDecodeTest {

	private static List<BuffJsonDecoder> decoders() {
		return List.of(BuffJson.decoder(), BuffJson.decoder().setGeneratedDecoders(false),
				BuffJson.decoder().setGeneratedDecoders(false).setTypedAccessors(false));
	}

	@Test
	void typedSchemasBindAllSupportedFieldKinds() {
		for (Message message : List.of(TestAllScalars.getDefaultInstance(), TestRepeatedScalars.getDefaultInstance(),
				TestNesting.getDefaultInstance(), TestMaps.getDefaultInstance(), TestOneof.getDefaultInstance(),
				TestOptionalFields.getDefaultInstance(), TestWrappers.getDefaultInstance(),
				TestEscapedJsonNames.getDefaultInstance(), TestAllTypesProto3.getDefaultInstance(),
				Money.getDefaultInstance())) {
			var schema = TypedMessageReaderSchema.forMessage(message);
			assertNotNull(schema);
			assertEquals(message.getDescriptorForType().getFields().size(), schema.typedFieldCount(),
					"Unexpected fallback for " + message.getDescriptorForType().getFullName());
		}
		assertNull(TypedMessageReaderSchema.forMessage(DynamicMessage.getDefaultInstance(TestMaps.getDescriptor())));
	}

	@Test
	void nestedCollectionsAndInputFormsSurviveSettingChanges() {
		var expected = TestMaps.newBuilder().putStringToMessage("日本", NestedMessage.newBuilder().setValue(42).build())
				.putStringToEnumValue("unknown", 12345).putUint64ToString(-1L, "max").build();
		String json = BuffJson.encoder().encode(expected);
		String padded = "prefix" + json + "suffix";
		byte[] bytes = padded.getBytes(StandardCharsets.UTF_8);
		int length = json.getBytes(StandardCharsets.UTF_8).length;
		var decoder = BuffJson.decoder().setGeneratedDecoders(false);
		for (boolean typed : List.of(true, false, true)) {
			assertSame(decoder, decoder.setTypedAccessors(typed));
			assertEquals(typed, decoder.getTypedAccessors());
			assertEquals(expected, decoder.decode(json, TestMaps.class));
			assertEquals(expected, decoder.decode(padded, 6, json.length(), TestMaps.class));
			assertEquals(expected, decoder.decode(bytes, 6, length, TestMaps.class));
			assertEquals(expected, decoder.decode(new ByteArrayInputStream(bytes, 6, length), TestMaps.class));
		}
	}

	@Test
	void messagesWithoutBuffJsonCodegenUseTypedSetters() throws Exception {
		var expected = Money.newBuilder().setCurrencyCode("EUR").setUnits(Long.MAX_VALUE).setNanos(123456789).build();
		String json = JsonFormat.printer().print(expected);
		assertFalse((Message) expected instanceof BuffJsonCodecHolder);
		for (var decoder : decoders()) {
			assertEquals(expected, decoder.decode(json, Money.class));
		}
	}

	@Test
	void missingNumericEnumSetterFallsBackForThatFieldOnly() {
		// protobuf's own descriptor message has closed enums without setTypeValue(int).
		// Its other setters remain usable; this is a fallback fixture, not a claim of
		// general proto2 JSON support.
		var expected = FieldDescriptorProto.newBuilder().setName("field").setNumber(1)
				.setType(FieldDescriptorProto.Type.TYPE_STRING).build();
		var schema = TypedMessageReaderSchema.forMessage(expected);
		assertTrue(schema.typedFieldCount() > 0);
		assertTrue(schema.typedFieldCount() < expected.getDescriptorForType().getFields().size());
		String json = "{\"name\":\"field\",\"number\":1,\"type\":\"TYPE_STRING\"}";
		assertEquals(expected, BuffJson.decoder().setGeneratedDecoders(false).decode(json, FieldDescriptorProto.class));
	}

	@Test
	void descriptorOnlyDecodeStaysDynamic() throws Exception {
		var expected = TestNesting.newBuilder().setNested(NestedMessage.newBuilder().setValue(42))
				.addRepeatedNested(NestedMessage.newBuilder().setName("nested")).build();
		try (var reader = JSONReader.of(BuffJson.encoder().encode(expected))) {
			Message actual = new ProtobufMessageReader(null, false).readMessage(reader, TestNesting.getDescriptor());
			assertEquals(DynamicMessage.parseFrom(TestNesting.getDescriptor(), expected.toByteString()), actual);
			assertInstanceOf(DynamicMessage.class,
					actual.getField(TestNesting.getDescriptor().findFieldByName("nested")));
		}
	}

	@Test
	void runtimeNullMapValuesAndUnknownEnumNumbersMatchReflection() {
		String json = """
				{"stringToMessage":{"empty":null,"value":{"value":7}},
				 "stringToEnum":{"zero":null,"unknown":12345},
				 "stringToInt32":{"zero":null},"stringToString":{"empty":null}}
				""";
		var typed = BuffJson.decoder().setGeneratedDecoders(false);
		var reflection = BuffJson.decoder().setGeneratedDecoders(false).setTypedAccessors(false);
		assertEquals(reflection.decode(json, TestMaps.class), typed.decode(json, TestMaps.class));
		assertEquals(12345, typed.decode(json, TestMaps.class).getStringToEnumValueOrThrow("unknown"));
	}

	@Test
	void canonicalTimestampsMatchReferenceAcrossPrecisions() throws Exception {
		var random = new Random(86400);
		var printer = JsonFormat.printer().omittingInsignificantWhitespace();
		var decoders = decoders();
		for (int i = 0; i < 2000; i++) {
			long seconds = random.nextLong(-62135596800L, 253402300800L);
			int nanos = switch (i % 4) {
				case 0 -> 0;
				case 1 -> random.nextInt(1000) * 1_000_000;
				case 2 -> random.nextInt(1_000_000) * 1000;
				default -> random.nextInt(1_000_000_000);
			};
			var expected = TestTimestamp.newBuilder()
					.setValue(Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos)).build();
			String json = printer.print(expected);
			for (var decoder : decoders) {
				assertEquals(expected, decoder.decode(json, TestTimestamp.class));
				assertEquals(expected, decoder.decode(json.getBytes(StandardCharsets.UTF_8), TestTimestamp.class));
			}
		}
	}

	@Test
	void timestampFastPathPreservesFallbacksAndValidation() {
		for (String text : List.of("0001-01-01T00:00:00Z", "9999-12-31T23:59:59.999999999Z", "2000-02-29T23:59:59Z",
				"2024-01-01T00:00:00.1Z", "2024-01-01T00:00:00.12345678Z", "2024-01-01T00:00:00+01:00",
				"2024-01-01t00:00:00z", "2016-12-31T23:59:60Z", "2024-01-01T24:00:00Z")) {
			var instant = Instant.parse(text);
			var expected = Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano())
					.build();
			for (var decoder : decoders()) {
				assertEquals(expected, decoder.decode("{\"value\":\"" + text + "\"}", TestTimestamp.class).getValue());
			}
		}
		for (String text : List.of("2023-02-29T00:00:00Z", "2024-13-01T00:00:00Z", "2024-01-00T00:00:00Z",
				"2024-01-01T25:00:00Z", "2024-01-01T00:60:00Z", "0000-01-01T00:00:00Z",
				"2024-01-01T00:00:00.1234567890Z", "2024-01-01T00:00:00.12xZ")) {
			for (var decoder : decoders()) {
				assertThrows(JSONException.class,
						() -> decoder.decode("{\"value\":\"" + text + "\"}", TestTimestamp.class));
			}
		}
	}

	@Test
	void configuredDecoderCanBeSharedBetweenThreads() throws Exception {
		var decoder = BuffJson.decoder().setGeneratedDecoders(false);
		var expected = TestRecursive.newBuilder().setValue(1).setChild(TestRecursive.newBuilder().setValue(2)).build();
		String json = BuffJson.encoder().encode(expected);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var work = java.util.stream.IntStream.range(0, 32).<Callable<Void>>mapToObj(i -> () -> {
				for (int j = 0; j < 100; j++)
					assertEquals(expected, decoder.decode(json, TestRecursive.class));
				return null;
			}).toList();
			for (var result : executor.invokeAll(work))
				result.get();
		}
	}
}
