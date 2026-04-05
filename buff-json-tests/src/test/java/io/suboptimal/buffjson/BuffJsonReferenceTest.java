package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.util.JsonFormat;

import org.junit.jupiter.api.Test;

import io.suboptimal.buffjson.proto.*;

class BuffJsonReferenceTest {

	private static final JsonFormat.Printer REFERENCE = JsonFormat.printer().omittingInsignificantWhitespace();
	private static final BuffJsonEncoder ENCODER = BuffJson.encoder();

	@Test
	void encodeScalarMessage() throws Exception {
		TestAllScalars message = TestAllScalars.newBuilder().setOptionalString("test").setOptionalInt32(42)
				.setOptionalInt64(1234567890L).setOptionalDouble(3.14).setOptionalBool(true).build();

		assertEquals(REFERENCE.print(message), ENCODER.encode(message));
	}

	@Test
	void encodeDefaultMessage() throws Exception {
		assertEquals("{}", ENCODER.encode(TestAllScalars.getDefaultInstance()));
	}

	@Test
	void encodeComplexMessage() throws Exception {
		NestedMessage nested = NestedMessage.newBuilder().setValue(1).setName("nested").build();

		TestNesting message = TestNesting.newBuilder().setNested(nested).addRepeatedNested(nested)
				.setEnumValue(TestEnum.TEST_ENUM_FOO).addRepeatedEnum(TestEnum.TEST_ENUM_BAR).build();

		assertEquals(REFERENCE.print(message), ENCODER.encode(message));
	}
}
