package me.niteshh.redcake.replication;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class RespCommandCodec {

    private RespCommandCodec() {
    }

    public static byte[] encode(List<String> command) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeAscii(output, "*" + command.size() + "\r\n");

        for (String argument : command) {
            byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
            writeAscii(output, "$" + bytes.length + "\r\n");
            output.writeBytes(bytes);
            writeAscii(output, "\r\n");
        }

        return output.toByteArray();
    }

    private static void writeAscii(
            ByteArrayOutputStream output,
            String value
    ) {
        output.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }
}
