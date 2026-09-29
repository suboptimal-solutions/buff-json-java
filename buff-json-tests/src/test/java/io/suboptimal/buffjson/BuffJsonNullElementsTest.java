package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.protobuf.util.JsonFormat;

import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.proto.TestNullElements;

/**
 * JSON {@code null} is a legitimate <em>value</em> for
 * {@code google.protobuf.Value} (it is {@code null_value}) and for
 * {@code google.protobuf.NullValue}, whether it stands as a list element, a map
 * value or a singular field. Every decoder must produce what {@code JsonFormat}
 * produces for such documents, on string and byte input alike.
 */
class BuffJsonNullElementsTest {

	private static final List<BuffJsonDecoder> DECODERS = List.of(BuffJson.decoder(),
			BuffJson.decoder().setFastPath(false), BuffJson.decoder().setGeneratedDecoders(false),
			BuffJson.decoder().setGeneratedDecoders(false).setTypedAccessors(false));

	private static final String[] NAMES = {"codegen (fast path)", "codegen (general)", "typed builder", "reflection"};

	private static TestNullElements reference(String json) throws Exception {
		TestNullElements.Builder builder = TestNullElements.newBuilder();
		JsonFormat.parser().merge(json, builder);
		return builder.build();
	}

	private static void assertAllDecodersMatchJsonFormat(String json) throws Exception {
		TestNullElements expected = reference(json);
		for (int i = 0; i < DECODERS.size(); i++) {
			BuffJsonDecoder decoder = DECODERS.get(i);
			assertEquals(expected, decoder.decode(json, TestNullElements.class), NAMES[i] + " / String: " + json);
			assertEquals(expected, decoder.decode(json.getBytes(StandardCharsets.UTF_8), TestNullElements.class),
					NAMES[i] + " / bytes: " + json);
		}
	}

	@Test
	void nullElementsOfARepeatedValueFieldAreNullValues() throws Exception {
		for (String json : new String[]{"{\"values\":[null]}", "{\"values\":[null,null]}",
				"{\"values\":[1,null,\"a\",null,true,{\"k\":null},[null,2]]}", "{\"values\":[null,{}]}",
				"{\"values\":[[null]]}"}) {
			assertAllDecodersMatchJsonFormat(json);
		}
		// the element is present, not dropped
		assertEquals(2, BuffJson.decoder().setGeneratedDecoders(false)
				.decode("{\"values\":[null,null]}", TestNullElements.class).getValuesCount());
		assertEquals(2, BuffJson.decoder().setGeneratedDecoders(false).setTypedAccessors(false)
				.decode("{\"values\":[null,null]}", TestNullElements.class).getValuesCount());
	}

	@Test
	void nullElementsOfARepeatedNullValueFieldAreNullValues() throws Exception {
		for (String json : new String[]{"{\"nullValues\":[null]}", "{\"nullValues\":[null,\"NULL_VALUE\",0,null]}"}) {
			assertAllDecodersMatchJsonFormat(json);
		}
		assertEquals(2, BuffJson.decoder().setGeneratedDecoders(false)
				.decode("{\"nullValues\":[null,null]}", TestNullElements.class).getNullValuesCount());
		assertEquals(2, BuffJson.decoder().setGeneratedDecoders(false).setTypedAccessors(false)
				.decode("{\"nullValues\":[null,null]}", TestNullElements.class).getNullValuesCount());
	}

	@Test
	void nullMapValuesAreNullValues() throws Exception {
		for (String json : new String[]{"{\"valueMap\":{\"a\":null}}", "{\"valueMap\":{\"a\":null,\"b\":1,\"c\":null}}",
				"{\"nullMap\":{\"a\":null}}", "{\"nullMap\":{\"a\":null,\"b\":\"NULL_VALUE\",\"c\":0}}"}) {
			assertAllDecodersMatchJsonFormat(json);
		}
	}

	@Test
	void singularNullsAndNestedDocuments() throws Exception {
		for (String json : new String[]{"{\"value\":null}", "{\"nullValue\":null}", "{\"list\":[null]}",
				"{\"list\":[null,1,[null]]}", "{\"strings\":[]}", "{\"strings\":null}", "{\"values\":null}",
				"{\"valueMap\":null}", "{\"nested\":[{\"values\":[null]},{\"nullMap\":{\"k\":null}},{}]}",
				"{\"nested\":[{\"nested\":[{\"values\":[null,null]}]}]}"}) {
			assertAllDecodersMatchJsonFormat(json);
		}
	}

	@Test
	void encodedNullElementsRoundTripThroughEveryDecoder() throws Exception {
		TestNullElements original = reference(
				"{\"values\":[null,1,null],\"nullValues\":[null],\"valueMap\":{\"a\":null},\"nullMap\":{\"b\":null},"
						+ "\"value\":null,\"list\":[null],\"nested\":[{\"values\":[null]}]}");
		String json = BuffJson.encoder().encode(original);
		for (int i = 0; i < DECODERS.size(); i++) {
			assertEquals(original, DECODERS.get(i).decode(json, TestNullElements.class), NAMES[i] + ": " + json);
		}
	}
}
