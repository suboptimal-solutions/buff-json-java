package io.suboptimal.buffjson.benchmarks;

import com.google.protobuf.util.JsonFormat;
import io.suboptimal.buffjson.BuffJSON;
import io.suboptimal.buffjson.proto.SimpleMessage;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
@State(Scope.Thread)
public class SimpleMessageBenchmark {

    private static final JsonFormat.Printer PROTO_PRINTER = JsonFormat.printer();

    private SimpleMessage message;

    @Setup
    public void setup() {
        message = BenchmarkData.createSimpleMessage();
    }

    @Benchmark
    public String buffJson() throws Exception {
        return BuffJSON.encode(message);
    }

    @Benchmark
    public String protoJsonFormat() throws Exception {
        return PROTO_PRINTER.print(message);
    }
}
