package io.suboptimal.buffjson;

import static org.junit.jupiter.api.Assertions.*;

import io.suboptimal.buffjson.proto.SimpleMessage;
import io.suboptimal.buffjson.proto.Status;
import org.junit.jupiter.api.Test;

class BuffJSONTest {

    @Test
    void encodeSimpleMessage() throws Exception {
        SimpleMessage message = SimpleMessage.newBuilder()
                .setName("test")
                .setId(42)
                .setTimestampMillis(1234567890L)
                .setScore(3.14)
                .setActive(true)
                .setStatus(Status.STATUS_ACTIVE)
                .build();

        String json = BuffJSON.encode(message);

        assertNotNull(json);
        assertTrue(json.contains("\"name\": \"test\""));
        assertTrue(json.contains("\"id\": 42"));
        assertTrue(json.contains("\"active\": true"));
    }

    @Test
    void encodeDefaultMessage() throws Exception {
        SimpleMessage message = SimpleMessage.getDefaultInstance();
        String json = BuffJSON.encode(message);
        assertNotNull(json);
        assertEquals("{\n}", json);
    }
}
