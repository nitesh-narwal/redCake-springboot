package me.niteshh.redcake.resp;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Serializes {@link RespValue}s into RESP bytes on an {@link OutputStream}.
 *
 * <p>Text is encoded as ISO-8859-1 (one char = one byte), the inverse of
 * {@link RespParser}'s decoding, which keeps values binary-safe.
 *
 * <p>The writer does not flush; the caller decides when to flush so that
 * pipelined replies can be batched into one TCP write.
 */
@Component
public class RespWriter {

    /**
     * Error codes clients know how to branch on. A message starting with one
     * of these is sent as-is; anything else gets the generic {@code ERR }
     * prefix. An explicit list (instead of "starts with capitals") avoids
     * mistaking text like "AOF is not enabled" or "DB index is out of range"
     * for an error code.
     */
    private static final Set<String> ERROR_CODES = Set.of(
            "ERR", "NOAUTH", "WRONGPASS", "READONLY", "MISCONF", "NOPROTO",
            "WRONGTYPE", "OOM", "LOADING", "BUSY", "NOPERM", "EXECABORT"
    );

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] NULL_ARRAY = "*-1\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] NULL_BULK = "$-1\r\n".getBytes(StandardCharsets.ISO_8859_1);

    /** Writes {@code value} (recursively for arrays) to {@code outputStream}. */
    public void write(RespValue value, OutputStream outputStream) throws IOException {
        switch (value) {
            case SimpleString simpleString -> writeSimpleString(simpleString, outputStream);
            case BulkString bulkString -> writeBulkString(bulkString, outputStream);
            case IntegerValue integerValue -> writeIntegerValue(integerValue, outputStream);
            case ErrorValue errorValue -> writeErrorValue(errorValue, outputStream);
            case NullValue ignored -> outputStream.write(NULL_BULK);
            case ArrayValue arrayValue -> writeArray(arrayValue, outputStream);
            case NullArray ignored -> outputStream.write(NULL_ARRAY);
        }
    }

    private void writeArray(ArrayValue array, OutputStream out) throws IOException {
        out.write(('*' + Integer.toString(array.elements().size()))
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(CRLF);
        for (RespValue element : array.elements()) {
            write(element, out);
        }
    }

    /**
     * Writes an error line. Newlines are replaced so a message containing
     * user input cannot smuggle extra RESP lines. Messages without an error
     * code (e.g. "wrong number of arguments...") get the generic {@code ERR }
     * prefix because client libraries branch on the code word.
     */
    private void writeErrorValue(ErrorValue errorValue, OutputStream out) throws IOException {
        String message = errorValue.message()
                .replace('\r', ' ')
                .replace('\n', ' ');
        int space = message.indexOf(' ');
        String firstWord = space < 0 ? message : message.substring(0, space);
        if (!ERROR_CODES.contains(firstWord)) {
            message = "ERR " + message;
        }
        out.write(('-' + message).getBytes(StandardCharsets.ISO_8859_1));
        out.write(CRLF);
    }

    private void writeIntegerValue(IntegerValue value, OutputStream out) throws IOException {
        out.write((':' + Long.toString(value.value())).getBytes(StandardCharsets.ISO_8859_1));
        out.write(CRLF);
    }

    private void writeBulkString(BulkString bulkString, OutputStream out) throws IOException {
        byte[] data = bulkString.value().getBytes(StandardCharsets.ISO_8859_1);
        out.write(('$' + Integer.toString(data.length)).getBytes(StandardCharsets.ISO_8859_1));
        out.write(CRLF);
        out.write(data);
        out.write(CRLF);
    }

    private void writeSimpleString(SimpleString simpleString, OutputStream out) throws IOException {
        String text = simpleString.value()
                .replace('\r', ' ')
                .replace('\n', ' ');
        out.write(('+' + text).getBytes(StandardCharsets.ISO_8859_1));
        out.write(CRLF);
    }
}
