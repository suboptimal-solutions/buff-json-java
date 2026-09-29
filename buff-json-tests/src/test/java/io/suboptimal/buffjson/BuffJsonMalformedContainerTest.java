package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.alibaba.fastjson2.JSONException;
import com.google.protobuf.Message;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf_test_messages.proto3.TestMessagesProto3.TestAllTypesProto3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.suboptimal.buffjson.proto.NestedMessage;
import io.suboptimal.buffjson.proto.TestNesting;

/**
 * Regression tests for untrusted JSON whose containers don't match the schema:
 * a non-object where a message, map, Struct, Any or Empty is expected, or a
 * non-array where a repeated field or ListValue is expected.
 *
 * <p>
 * Before the fix, a message reader that met such a token returned without
 * consuming it. Inside a repeated field the enclosing array loop then saw the
 * same token forever, appending empty messages until {@code OutOfMemoryError}
 * (for example {@code {"repeatedNested":[1]}} on every decode path, or
 * {@code {"repeatedNested":[null]}} on the codegen path). Every case runs on
 * all three decode paths under a timeout, so a regression is reported as a
 * failure at the timeout rather than a hung build. The timed-out decode thread
 * cannot be stopped (the loop never checks for interruption), so after such a
 * failure the forked test JVM may still run out of memory.
 */
class BuffJsonMalformedContainerTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final TypeRegistry REGISTRY = TypeRegistry.newBuilder().add(TestAllTypesProto3.getDescriptor())
			.add(NestedMessage.getDescriptor()).build();

	private static Map<String, BuffJsonDecoder> paths() {
		Map<String, BuffJsonDecoder> paths = new LinkedHashMap<>();
		paths.put("codegen", BuffJson.decoder().setTypeRegistry(REGISTRY));
		paths.put("typed", BuffJson.decoder().setGeneratedDecoders(false).setTypeRegistry(REGISTRY));
		paths.put("reflection",
				BuffJson.decoder().setGeneratedDecoders(false).setTypedAccessors(false).setTypeRegistry(REGISTRY));
		return paths;
	}

	private static Stream<Arguments> onAllPaths(Class<? extends Message> type, String... inputs) {
		List<Arguments> args = new ArrayList<>();
		for (var path : paths().entrySet()) {
			for (String json : inputs) {
				args.add(Arguments.of(path.getKey(), path.getValue(), type, json));
			}
		}
		return args.stream();
	}

	private static void assertRejected(BuffJsonDecoder decoder, Class<? extends Message> type, String json) {
		assertTimeoutPreemptively(TIMEOUT, () -> {
			assertThrows(JSONException.class, () -> decoder.decode(json, type), json);
		});
	}

	/** Must finish within the timeout, either decoding or with a JSONException. */
	private static void assertTerminates(BuffJsonDecoder decoder, Class<? extends Message> type, String json) {
		assertTimeoutPreemptively(TIMEOUT, () -> {
			try {
				decoder.decode(json, type);
			} catch (JSONException rejected) {
				// either outcome is fine; only termination is asserted
			}
		}, json);
	}

	// =========================================================================
	// Repeated message elements that are not objects (the infinite loop)
	// =========================================================================

	static Stream<Arguments> nonObjectRepeatedMessageElements() {
		return Stream.concat(
				onAllPaths(TestNesting.class, "{\"repeatedNested\":[1]}", "{\"repeatedNested\":[true]}",
						"{\"repeatedNested\":[false]}", "{\"repeatedNested\":[\"x\"]}", "{\"repeatedNested\":[[]]}",
						"{\"repeatedNested\":[{\"value\":1},2]}", "{\"repeatedNested\":[{\"value\":1},[]]}"),
				onAllPaths(TestAllTypesProto3.class, "{\"repeatedNestedMessage\":[1]}",
						"{\"repeatedForeignMessage\":[true]}", "{\"repeatedStruct\":[1]}", "{\"repeatedStruct\":[[1]]}",
						"{\"repeatedListValue\":[1]}", "{\"repeatedListValue\":[{}]}", "{\"repeatedAny\":[1]}",
						"{\"repeatedAny\":[[]]}", "{\"repeatedEmpty\":[1]}", "{\"repeatedEmpty\":[[]]}"));
	}

	@ParameterizedTest(name = "{0}: {3}")
	@MethodSource
	void nonObjectRepeatedMessageElements(String path, BuffJsonDecoder decoder, Class<? extends Message> type,
			String json) {
		assertRejected(decoder, type, json);
	}

	// =========================================================================
	// Repeated / map fields whose value is the wrong container
	// =========================================================================

	static Stream<Arguments> nonArrayRepeatedValues() {
		return Stream.concat(
				onAllPaths(TestNesting.class, "{\"repeatedNested\":5}", "{\"repeatedNested\":{}}",
						"{\"repeatedNested\":\"x\"}", "{\"repeatedNested\":true}", "{\"repeatedEnum\":\"FOO\"}"),
				onAllPaths(TestAllTypesProto3.class, "{\"repeatedInt32\":5}", "{\"repeatedString\":\"a\"}",
						"{\"repeatedStruct\":{}}", "{\"repeatedValue\":1}", "{\"repeatedTimestamp\":\"x\"}"));
	}

	@ParameterizedTest(name = "{0}: {3}")
	@MethodSource
	void nonArrayRepeatedValues(String path, BuffJsonDecoder decoder, Class<? extends Message> type, String json) {
		assertRejected(decoder, type, json);
	}

	static Stream<Arguments> nonObjectMapValues() {
		return onAllPaths(TestAllTypesProto3.class, "{\"mapStringString\":[]}", "{\"mapStringString\":5}",
				"{\"mapStringNestedMessage\":5}", "{\"mapStringNestedMessage\":{\"k\":1}}",
				"{\"mapStringNestedMessage\":{\"k\":[]}}");
	}

	@ParameterizedTest(name = "{0}: {3}")
	@MethodSource
	void nonObjectMapValues(String path, BuffJsonDecoder decoder, Class<? extends Message> type, String json) {
		assertRejected(decoder, type, json);
	}

	// =========================================================================
	// Singular message values and top-level input that are not objects
	// =========================================================================

	static Stream<Arguments> nonObjectSingularMessages() {
		return Stream.concat(onAllPaths(TestNesting.class, "{\"nested\":1}", "{\"nested\":[]}", "1", "[]", "true"),
				onAllPaths(TestAllTypesProto3.class, "{\"optionalNestedMessage\":1}", "{\"optionalStruct\":1}",
						"{\"optionalStruct\":[1]}", "{\"optionalAny\":1}", "{\"optionalEmpty\":1}",
						"{\"optionalEmpty\":[]}", "{\"recursiveMessage\":{\"recursiveMessage\":1}}"));
	}

	@ParameterizedTest(name = "{0}: {3}")
	@MethodSource
	void nonObjectSingularMessages(String path, BuffJsonDecoder decoder, Class<? extends Message> type, String json) {
		assertRejected(decoder, type, json);
	}

	// =========================================================================
	// Malformed objects inside containers
	// =========================================================================

	static Stream<Arguments> malformedObjectsInsideContainers() {
		// A member without a name ends the object early (unchanged behavior); the
		// leftover token is then rejected by the enclosing reader or by the top-level
		// trailing-input check instead of being re-read forever.
		return Stream.concat(
				onAllPaths(TestNesting.class, "{\"nested\":{\"value\":1, 2}}",
						"{\"repeatedNested\":[{\"value\":1, 2}]}", "{\"repeatedNested\":[{\"value\":1, true}]}"),
				onAllPaths(TestAllTypesProto3.class, "{\"mapStringString\":{\"a\":\"b\", 1}}",
						"{\"repeatedStruct\":[{\"a\":1, 2}]}", "{\"optionalStruct\":{\"a\":1, 2}}",
						"{\"optionalEmpty\":{1}}", "{\"repeatedEmpty\":[{1}]}", "{\"optionalAny\":{\"a\":1, 2}}",
						"{\"optionalAny\":{\"@type\":\"type.googleapis.com/google.protobuf.Timestamp\", 5}}"));
	}

	@ParameterizedTest(name = "{0}: {3}")
	@MethodSource
	void malformedObjectsInsideContainers(String path, BuffJsonDecoder decoder, Class<? extends Message> type,
			String json) {
		assertRejected(decoder, type, json);
	}

	static Stream<Arguments> truncatedInput() {
		return Stream.concat(
				onAllPaths(TestNesting.class, "{\"repeatedNested\":[", "{\"repeatedNested\":[{",
						"{\"repeatedNested\":[{\"value\":1", "{\"repeatedNested\":[{\"value\":1},"),
				onAllPaths(TestAllTypesProto3.class, "{\"repeatedStruct\":[{\"a\":1", "{\"repeatedListValue\":[[1",
						"{\"repeatedEmpty\":[{", "{\"optionalEmpty\":{", "{\"mapStringNestedMessage\":{\"k\":{"));
	}

	/**
	 * Input cut off mid-container must terminate. Truncated objects are accepted
	 * leniently at end of input (see {@code BuffJsonErrorTest}), so either outcome
	 * is fine here; only termination is pinned.
	 */
	@ParameterizedTest(name = "{0}: {3}")
	@MethodSource
	void truncatedInput(String path, BuffJsonDecoder decoder, Class<? extends Message> type, String json) {
		assertTerminates(decoder, type, json);
	}

	// =========================================================================
	// Null elements in repeated message fields
	// =========================================================================

	static Stream<Arguments> nullRepeatedMessageElements() {
		return Stream.concat(onAllPaths(TestNesting.class, "{\"repeatedNested\":[null]}"),
				onAllPaths(TestAllTypesProto3.class, "{\"repeatedNestedMessage\":[{\"a\":1},null,{\"a\":2}]}",
						"{\"repeatedStruct\":[null]}", "{\"repeatedListValue\":[null]}", "{\"repeatedAny\":[null]}",
						"{\"repeatedEmpty\":[null]}"));
	}

	/**
	 * A null element used to loop forever on the codegen path. It now terminates on
	 * every path. Codegen rejects it (as {@code JsonFormat} does), while the
	 * runtime paths keep their existing behavior of skipping null elements; either
	 * outcome is accepted here so this test only pins termination.
	 */
	@ParameterizedTest(name = "{0}: {3}")
	@MethodSource
	void nullRepeatedMessageElements(String path, BuffJsonDecoder decoder, Class<? extends Message> type, String json) {
		assertTerminates(decoder, type, json);
	}

	static Stream<Arguments> nullRepeatedScalarAndWktElements() {
		return Stream.of("{\"repeatedTimestamp\":[null]}", "{\"repeatedDuration\":[null]}",
				"{\"repeatedFieldmask\":[null]}", "{\"repeatedStringWrapper\":[null]}",
				"{\"repeatedBytesWrapper\":[null]}", "{\"repeatedInt32Wrapper\":[null]}", "{\"repeatedInt64\":[null]}",
				"{\"repeatedUint64\":[null]}", "{\"repeatedBool\":[null]}", "{\"repeatedDouble\":[null]}",
				"{\"repeatedNestedEnum\":[null]}", "{\"repeatedInt32\":[1,null]}").map(Arguments::of);
	}

	/**
	 * Codegen rejects a null element with a {@link JSONException} for every type
	 * but Value/NullValue: it used to NPE for Timestamp/Duration/FieldMask/
	 * String/BytesValue and add a phantom default element for int64/bool/enum/...
	 * The runtime paths skip it (either way no default element is added).
	 */
	@ParameterizedTest
	@MethodSource
	void nullRepeatedScalarAndWktElements(String json) {
		var paths = paths();
		assertRejected(paths.get("codegen"), TestAllTypesProto3.class, json);
		TestAllTypesProto3 expected = json.startsWith("{\"repeatedInt32\":")
				? TestAllTypesProto3.newBuilder().addRepeatedInt32(1).build()
				: TestAllTypesProto3.getDefaultInstance();
		for (String runtime : List.of("typed", "reflection")) {
			BuffJsonDecoder decoder = paths.get(runtime);
			assertEquals(expected,
					assertTimeoutPreemptively(TIMEOUT, () -> decoder.decode(json, TestAllTypesProto3.class)),
					runtime + ": " + json);
		}
	}

	@Test
	void nullRepeatedValueElementsArePreservedOnEveryPath() throws Exception {
		var expected = TestAllTypesProto3.newBuilder()
				.addRepeatedValue(
						com.google.protobuf.Value.newBuilder().setNullValue(com.google.protobuf.NullValue.NULL_VALUE))
				.addRepeatedValue(com.google.protobuf.Value.newBuilder().setNumberValue(1)).build();
		String json = "{\"repeatedValue\":[null,1]}";
		var reference = TestAllTypesProto3.newBuilder();
		com.google.protobuf.util.JsonFormat.parser().merge(json, reference);
		assertEquals(expected, reference.build());
		for (var path : paths().entrySet()) {
			assertEquals(expected, path.getValue().decode(json, TestAllTypesProto3.class), path.getKey());
			assertEquals(expected,
					path.getValue().decode(BuffJson.encoder().encode(expected), TestAllTypesProto3.class),
					path.getKey() + " round trip");
		}
	}

	@Test
	void nullRepeatedNullValueElementsArePreservedOnEveryPath() throws Exception {
		var expected = io.suboptimal.buffjson.proto.TestRepeatedNullValue.newBuilder()
				.addValues(com.google.protobuf.NullValue.NULL_VALUE).addValues(com.google.protobuf.NullValue.NULL_VALUE)
				.build();
		String json = "{\"values\":[null,null]}";
		var reference = io.suboptimal.buffjson.proto.TestRepeatedNullValue.newBuilder();
		com.google.protobuf.util.JsonFormat.parser().merge(json, reference);
		assertEquals(expected, reference.build());
		for (var path : paths().entrySet()) {
			assertEquals(expected,
					path.getValue().decode(json, io.suboptimal.buffjson.proto.TestRepeatedNullValue.class),
					path.getKey());
			assertEquals(expected, path.getValue().decode(BuffJson.encoder().encode(expected),
					io.suboptimal.buffjson.proto.TestRepeatedNullValue.class), path.getKey() + " round trip");
		}
	}

	/**
	 * Empty input decodes to {@code null} from every overload, instead of failing
	 * the new object-start check (an empty {@code InputStream} or whitespace-only
	 * body used to decode to an empty message).
	 */
	@Test
	void emptyInputDecodesToNullFromEveryOverload() {
		byte[] blank = "  \n ".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		for (var path : paths().entrySet()) {
			BuffJsonDecoder decoder = path.getValue();
			assertNull(decoder.decode("", TestNesting.class), path.getKey());
			assertNull(decoder.decode("  \n ", TestNesting.class), path.getKey());
			assertNull(decoder.decode(new byte[0], TestNesting.class), path.getKey());
			assertNull(decoder.decode(blank, TestNesting.class), path.getKey());
			assertNull(decoder.decode("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), 1, 0, TestNesting.class),
					path.getKey());
			assertNull(decoder.decode(new java.io.ByteArrayInputStream(new byte[0]), TestNesting.class), path.getKey());
			assertNull(decoder.decode(new java.io.ByteArrayInputStream(blank), TestNesting.class), path.getKey());
			assertEquals(TestNesting.getDefaultInstance(),
					decoder.decode(
							new java.io.ByteArrayInputStream("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
							TestNesting.class),
					path.getKey());
		}
	}

	// =========================================================================
	// Error message and valid input
	// =========================================================================

	@Test
	void errorNamesTheExpectedTypeAndOffsetOnEveryPath() {
		for (var path : paths().entrySet()) {
			JSONException ex = assertTimeoutPreemptively(TIMEOUT, () -> assertThrows(JSONException.class,
					() -> path.getValue().decode("{\"repeatedNested\":[{\"value\":1},7]}", TestNesting.class)));
			assertTrue(
					ex.getMessage()
							.contains("Expected a JSON object for message io.suboptimal.buffjson.proto.NestedMessage"),
					path.getKey() + ": " + ex.getMessage());
			assertTrue(ex.getMessage().contains("offset"), path.getKey() + ": " + ex.getMessage());
		}
	}

	@Test
	void wellFormedContainersStillDecodeIdenticallyOnEveryPath() {
		var expected = TestNesting.newBuilder().setNested(NestedMessage.newBuilder().setValue(1).setName("a"))
				.addRepeatedNested(NestedMessage.newBuilder().setValue(2))
				.addRepeatedNested(NestedMessage.getDefaultInstance()).addRepeatedEnumValue(1).build();
		String json = "{ \"nested\" : { \"value\" : 1 , \"name\" : \"a\" } , \"repeatedNested\" : [ { \"value\" : 2 } , { } ] ,"
				+ " \"repeated_enum\" : [ \"TEST_ENUM_FOO\" ] , \"unknown\" : [ 1 , { \"x\" : [ ] } ] }";
		for (var path : paths().entrySet()) {
			assertEquals(expected, path.getValue().decode(json, TestNesting.class), path.getKey());
		}
	}
}
