package io.suboptimal.buffjson.benchmarks;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.suboptimal.buffjson.proto.Address;
import io.suboptimal.buffjson.proto.ComplexMessage;
import io.suboptimal.buffjson.proto.SimpleMessage;
import io.suboptimal.buffjson.proto.Status;
import io.suboptimal.buffjson.proto.Tag;

public final class BenchmarkData {

    private BenchmarkData() {}

    public static SimpleMessage createSimpleMessage() {
        return SimpleMessage.newBuilder()
                .setName("benchmark-user")
                .setId(42)
                .setTimestampMillis(1711627200000L)
                .setScore(99.95)
                .setActive(true)
                .setStatus(Status.STATUS_ACTIVE)
                .build();
    }

    public static ComplexMessage createComplexMessage() {
        Timestamp now = Timestamp.newBuilder()
                .setSeconds(1711627200L)
                .setNanos(0)
                .build();

        Address primaryAddr = Address.newBuilder()
                .setStreet("123 Main St")
                .setCity("Springfield")
                .setState("IL")
                .setZipCode("62704")
                .setCountry("US")
                .build();

        Address secondaryAddr = Address.newBuilder()
                .setStreet("456 Oak Ave")
                .setCity("Shelbyville")
                .setState("IL")
                .setZipCode("62565")
                .setCountry("US")
                .build();

        return ComplexMessage.newBuilder()
                .setId("msg-001")
                .setName("complex-benchmark")
                .setVersion(1)
                .setPrimaryAddress(primaryAddr)
                .addTagsList("java")
                .addTagsList("protobuf")
                .addTagsList("benchmark")
                .addAddresses(primaryAddr)
                .addAddresses(secondaryAddr)
                .addTags(Tag.newBuilder().setKey("env").setValue("prod").build())
                .addTags(Tag.newBuilder().setKey("region").setValue("us-east").build())
                .putMetadata("version", "1.0")
                .putMetadata("format", "json")
                .putMetadata("encoding", "utf-8")
                .putAddressBook(1, primaryAddr)
                .setEmail("user@example.com")
                .setPayload(ByteString.copyFromUtf8("binary-payload-data"))
                .setCreatedAt(now)
                .setUpdatedAt(now)
                .setStatus(Status.STATUS_ACTIVE)
                .build();
    }
}
