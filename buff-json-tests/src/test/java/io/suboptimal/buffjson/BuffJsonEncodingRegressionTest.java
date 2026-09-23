package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.google.protobuf.*;
import com.google.protobuf_test_messages.proto3.TestMessagesProto3.TestAllTypesProto3;
import com.google.protobuf.util.JsonFormat;

import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.internal.ProtobufMessageWriter;
import io.suboptimal.buffjson.internal.typed.TypedMessageSchema;
import io.suboptimal.buffjson.proto.*;

class BuffJsonEncodingRegressionTest {

	private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();
	private static List<BuffJsonEncoder> encoders() {
		return List.of(BuffJson.encoder(), BuffJson.encoder().setGeneratedEncoders(false),
				BuffJson.encoder().setGeneratedEncoders(false).setTypedAccessors(false));
	}

	private static TestEscapedJsonNames escapedNames() {
		return TestEscapedJsonNames.newBuilder().setUnicode(42).setQuoted("quote").setBackslash(true).setControls(-1)
				.setLiteralEscape("literal").addNumbers(1).addNumbers(2).putLabels("key", "value").setPresent(0)
				.setChosen("choice").setNested(NestedMessage.newBuilder().setValue(3)).build();
	}

	private static String escapedNamesJson(TestEscapedJsonNames message) throws Exception {
		// JsonFormat 4.34.1 prints custom json_name without escaping it. Use its
		// valid proto-name output as the value oracle, then rename the keys.
		var values = JSON.parseObject(PRINTER.preservingProtoFieldNames().print(message));
		Map<String, Object> renamed = new LinkedHashMap<>();
		for (var field : message.getDescriptorForType().getFields()) {
			if (values.containsKey(field.getName()))
				renamed.put(field.getJsonName(), values.get(field.getName()));
		}
		return JSON.toJSONString(renamed);
	}

	@Test
	void escapedNamesEncodeAcrossAllPaths() throws Exception {
		var original = escapedNames();
		assertNotNull(TypedMessageSchema.forMessage(original.getDescriptorForType(), original.getClass()),
				"Custom names must not force a fallback to reflection");
		var expected = JSON.parseObject(escapedNamesJson(original));
		assertTrue(expected.containsKey("naïve日本🚀"));
		assertTrue(expected.containsKey("literal\\u000a"));
		for (var message : List.of(original,
				DynamicMessage.parseFrom(original.getDescriptorForType(), original.toByteString()))) {
			for (var encoder : encoders()) {
				for (String json : List.of(encoder.encode(message),
						new String(encoder.encodeToBytes(message), StandardCharsets.UTF_8))) {
					assertEquals(expected, JSON.parseObject(json));
					var builder = TestEscapedJsonNames.newBuilder();
					JsonFormat.parser().merge(json, builder);
					assertEquals(original, builder.build());
				}
			}
		}
	}

	@Test
	void escapedNamesAndProtoAliasesDecode() throws Exception {
		var original = escapedNames();
		for (String json : List.of(escapedNamesJson(original), PRINTER.preservingProtoFieldNames().print(original))) {
			for (boolean generated : List.of(true, false)) {
				var decoder = BuffJson.decoder().setGeneratedDecoders(generated);
				assertEquals(original, decoder.decode(json, TestEscapedJsonNames.class));
				assertEquals(original,
						decoder.decode(json.getBytes(StandardCharsets.UTF_8), TestEscapedJsonNames.class));
			}
		}
	}

	@Test
	void wellKnownTypesMatchForConcreteAndDynamicMessages() throws Exception {
		var struct = Struct.newBuilder().putFields("quoted\"key", Value.newBuilder().setStringValue("value").build())
				.build();
		var list = ListValue.newBuilder().addValues(Value.newBuilder().setNullValue(NullValue.NULL_VALUE))
				.addValues(Value.newBuilder().setBoolValue(true)).addValues(Value.newBuilder().setNumberValue(-1.5))
				.addValues(Value.newBuilder().setStringValue("text"))
				.addValues(Value.newBuilder().setStructValue(struct))
				.addValues(Value.newBuilder().setListValue(ListValue.getDefaultInstance())).build();
		for (Message original : List.of(
				TestTimestamp.newBuilder().setValue(Timestamp.newBuilder().setSeconds(-1).setNanos(123456789)).build(),
				TestDuration.newBuilder().setValue(Duration.newBuilder().setSeconds(-2).setNanos(-123456789)).build(),
				TestStruct.newBuilder().setStructValue(struct).setValue(Value.newBuilder().setListValue(list))
						.setListValue(list).build(),
				TestStruct.newBuilder().setStructValue(Struct.getDefaultInstance())
						.setListValue(ListValue.getDefaultInstance()).build())) {
			Message dynamic = DynamicMessage.parseFrom(original.getDescriptorForType(), original.toByteString());
			assertInstanceOf(DynamicMessage.class,
					dynamic.getField(original.getDescriptorForType().getFields().getFirst()));
			String expected = PRINTER.print(original);
			assertEquals(expected, PRINTER.print(dynamic));
			for (var encoder : encoders()) {
				for (var message : List.of(original, dynamic)) {
					assertEquals(expected, encoder.encode(message));
					assertEquals(expected, new String(encoder.encodeToBytes(message), StandardCharsets.UTF_8));
				}
			}
		}
	}

	@Test
	void repeatedTimestampAndDurationMatchAcrossAllPaths() throws Exception {
		var original = TestAllTypesProto3.newBuilder()
				.addRepeatedTimestamp(Timestamp.newBuilder().setSeconds(-1).setNanos(123456789))
				.addRepeatedTimestamp(Timestamp.newBuilder().setSeconds(1711627200).setNanos(123000000))
				.addRepeatedDuration(Duration.newBuilder().setSeconds(-2).setNanos(-123456789))
				.addRepeatedDuration(Duration.newBuilder().setSeconds(3).setNanos(250000000)).build();
		String expected = PRINTER.print(original);
		for (var encoder : encoders()) {
			assertEquals(expected, encoder.encode(original));
			assertEquals(expected, new String(encoder.encodeToBytes(original), StandardCharsets.UTF_8));
		}
	}

	@Test
	void booleanMapKeysStayBooleanNamesWithNumericBooleanFeature() throws Exception {
		var message = TestMaps.newBuilder().putBoolToString(true, "yes").putBoolToString(false, "no").build();
		String expected = PRINTER.print(message);
		for (var writer : List.of(new ProtobufMessageWriter(null, true, true),
				new ProtobufMessageWriter(null, false, true), new ProtobufMessageWriter(null, false, false))) {
			for (boolean utf8 : List.of(false, true)) {
				try (var jw = utf8
						? JSONWriter.ofUTF8(JSONWriter.Feature.WriteBooleanAsNumber)
						: JSONWriter.of(JSONWriter.Feature.WriteBooleanAsNumber)) {
					writer.writeMessage(jw, message);
					assertEquals(expected, jw.toString());
				}
			}
		}
	}

	@Test
	void numericMapKeysIgnoreNumberFormattingFeatures() throws Exception {
		var message = TestMaps.newBuilder().putInt64ToString(Long.MIN_VALUE, "min")
				.putInt64ToString(Long.MAX_VALUE, "max").putInt64ToString(1, "small")
				.putUint64ToString(-1, "unsigned max").putUint64ToString(Long.MAX_VALUE, "unsigned signed max")
				.putUint32ToString(1, "small uint32").putUint32ToString(-1, "uint32 max").build();
		String expected = PRINTER.print(message);
		for (var feature : List.of(JSONWriter.Feature.BrowserCompatible, JSONWriter.Feature.WriteClassName)) {
			for (var writer : List.of(new ProtobufMessageWriter(null, true, true),
					new ProtobufMessageWriter(null, false, true), new ProtobufMessageWriter(null, false, false))) {
				for (boolean utf8 : List.of(false, true)) {
					try (var jw = utf8 ? JSONWriter.ofUTF8(feature) : JSONWriter.of(feature)) {
						writer.writeMessage(jw, message);
						assertEquals(expected, jw.toString());
					}
				}
			}
		}
	}

	@Test
	void invalidWellKnownTypesStillRejectDynamicMessages() throws Exception {
		for (Message original : List.of(
				TestTimestamp.newBuilder().setValue(Timestamp.newBuilder().setSeconds(253402300800L)).build(),
				TestTimestamp.newBuilder().setValue(Timestamp.newBuilder().setNanos(-1)).build(),
				TestDuration.newBuilder().setValue(Duration.newBuilder().setSeconds(315576000001L)).build(),
				TestDuration.newBuilder().setValue(Duration.newBuilder().setSeconds(1).setNanos(-1)).build())) {
			var dynamic = DynamicMessage.parseFrom(original.getDescriptorForType(), original.toByteString());
			for (var encoder : encoders()) {
				for (var message : List.of(original, dynamic)) {
					assertThrows(IllegalArgumentException.class, () -> encoder.encode(message));
					assertThrows(IllegalArgumentException.class, () -> encoder.encodeToBytes(message));
				}
			}
		}
	}
}
