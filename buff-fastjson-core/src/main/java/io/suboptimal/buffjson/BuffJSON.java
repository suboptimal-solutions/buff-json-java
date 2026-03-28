package io.suboptimal.buffjson;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;

public final class BuffJSON {

    private static final JsonFormat.Printer PRINTER = JsonFormat.printer();

    private BuffJSON() {}

    public static String encode(MessageOrBuilder message) throws InvalidProtocolBufferException {
        return PRINTER.print(message);
    }
}
