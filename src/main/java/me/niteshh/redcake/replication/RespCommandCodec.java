package me.niteshh.redcake.replication;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes a command (list of words) as a RESP array of bulk strings - the
 * exact byte format used on the replication stream, in the append-only file
 * and by client requests. One canonical encoding means the AOF loader can
 * compute record lengths by re-encoding.
 */
public final class RespCommandCodec {

    private RespCommandCodec() {
    }

    /** @return {@code *<n>\r\n} followed by {@code $<len>\r\n<bytes>\r\n} per word */
    public static byte[] encode(List<String> command) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(64);
        writeAscii(output, "*" + command.size() + "\r\n");

        for (String argument : command) {
            byte[] bytes = argument.getBytes(StandardCharsets.ISO_8859_1);
            writeAscii(output, "$" + bytes.length + "\r\n");
            output.writeBytes(bytes);
            writeAscii(output, "\r\n");
        }
        return output.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.ISO_8859_1));
    }
}
