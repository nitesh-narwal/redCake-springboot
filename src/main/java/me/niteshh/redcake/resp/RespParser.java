package me.niteshh.redcake.resp;

import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental RESP request parser.
 *
 * <p><b>Binary safety:</b> every argument is decoded as ISO-8859-1, a charset
 * that maps each byte 0-255 to exactly one char and back. A Java
 * {@code String} therefore acts as a lossless "byte string": images, protobuf
 * or compressed blobs round-trip unchanged (UTF-8 decoding would have
 * replaced invalid sequences with U+FFFD). As a bonus Java stores Latin-1
 * strings with one byte per char, so memory use matches the real byte length.
 *
 * <p>TCP is a byte stream: one {@code read()} may contain half a command or
 * several commands. The parser therefore pulls exactly one complete command
 * from the stream per call and leaves the rest buffered for the next call,
 * which is what makes pipelining work.
 *
 * <p>Two entry points exist:
 * <ul>
 *   <li>{@link #parseCommand} - strict RESP arrays only. Used for the
 *       replication stream and the append-only file, where a non-array byte
 *       (for example an {@code -ERR} line) must be an error, not a command.</li>
 *   <li>{@link #parseClientCommand} - additionally accepts <em>inline</em>
 *       commands such as {@code PING\r\n}, so {@code telnet} and {@code nc}
 *       work like they do with real Redis.</li>
 * </ul>
 *
 * <p>All size limits exist to stop a hostile peer from forcing large
 * allocations before it has sent the data it promised.
 */
@Component
public class RespParser {

    /** Max arguments per command (large enough for MSET/MGET batches). */
    static final int MAX_COMMAND_ELEMENTS = 1024;
    /** Max size of one bulk string argument. */
    static final int MAX_BULK_STRING_BYTES = 1024 * 1024;
    /** Max total payload bytes of a single command (all arguments together). */
    static final int MAX_COMMAND_BYTES = 8 * 1024 * 1024;
    /** Max length of a RESP header line such as {@code $12345}. */
    private static final int MAX_LINE_BYTES = 64;
    /** Max length of an inline command line. */
    private static final int MAX_INLINE_BYTES = 64 * 1024;

    /**
     * Reads one strict RESP array command.
     *
     * @return the command words, or {@code null} on clean end of stream
     * @throws ProtocolException if the bytes are not a valid RESP array
     * @throws IOException       on socket failure
     */
    public List<String> parseCommand(BufferedInputStream input) throws IOException {
        int firstByte = input.read();
        if (firstByte == -1) {
            return null;
        }
        if (firstByte != '*') {
            throw new ProtocolException(
                    "Expected RESP array but received: " + (char) firstByte
            );
        }
        return parseArray(input);
    }

    /**
     * Reads one client command: a RESP array or an inline text line.
     * Blank inline lines are skipped, matching Redis.
     *
     * @return the command words, or {@code null} on clean end of stream
     */
    public List<String> parseClientCommand(BufferedInputStream input) throws IOException {
        while (true) {
            int firstByte = input.read();
            if (firstByte == -1) {
                return null;
            }
            if (firstByte == '*') {
                return parseArray(input);
            }
            List<String> inline = parseInline(input, firstByte);
            if (!inline.isEmpty()) {
                return inline;
            }
        }
    }

    /** Parses the part of an array command after the leading '*'. */
    private List<String> parseArray(BufferedInputStream input) throws IOException {
        int numberOfElements = readInteger(input);
        if (numberOfElements < 1 || numberOfElements > MAX_COMMAND_ELEMENTS) {
            throw new ProtocolException(
                    "Command must contain between 1 and "
                            + MAX_COMMAND_ELEMENTS + " elements"
            );
        }

        // Cap the initial capacity: the peer controls numberOfElements.
        List<String> command = new ArrayList<>(Math.min(numberOfElements, 16));
        long totalBytes = 0;

        for (int i = 0; i < numberOfElements; i++) {
            if (input.read() != '$') {
                throw new ProtocolException("Expected bulk string");
            }

            int length = readInteger(input);
            if (length < 0 || length > MAX_BULK_STRING_BYTES) {
                throw new ProtocolException(
                        "Bulk string length is invalid or exceeds "
                                + MAX_BULK_STRING_BYTES + " bytes"
                );
            }
            totalBytes += length;
            if (totalBytes > MAX_COMMAND_BYTES) {
                throw new ProtocolException(
                        "Command exceeds " + MAX_COMMAND_BYTES + " bytes"
                );
            }

            byte[] data = input.readNBytes(length);
            if (data.length != length) {
                throw new ProtocolException("Unexpected end of stream");
            }

            readCRLF(input);
            command.add(new String(data, StandardCharsets.ISO_8859_1));
        }

        return command;
    }

    /** Parses an inline command: words separated by spaces/tabs. */
    private List<String> parseInline(BufferedInputStream input, int firstByte)
            throws IOException {
        StringBuilder line = new StringBuilder();
        int current = firstByte;
        while (current != '\n') {
            if (current == -1) {
                throw new ProtocolException("Unexpected end of inline command");
            }
            if (line.length() >= MAX_INLINE_BYTES) {
                throw new ProtocolException("Inline command is too long");
            }
            line.append((char) current);
            current = input.read();
        }

        String trimmed = line.toString().strip();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return List.of(trimmed.split("[ \\t]+"));
    }

    /**
     * Reads a CRLF-terminated decimal integer without allocating a String.
     * Rejects empty values, non-digits, overflow and missing LF.
     */
    private int readInteger(BufferedInputStream input) throws IOException {
        long value = 0;
        boolean negative = false;
        int digits = 0;
        int b;

        while ((b = input.read()) != -1) {
            if (b == '\r') {
                if (input.read() != '\n') {
                    throw new ProtocolException("Invalid RESP line ending");
                }
                if (digits == 0) {
                    throw new ProtocolException(
                            "Expected integer but received empty value"
                    );
                }
                return (int) (negative ? -value : value);
            }

            if (digits + (negative ? 1 : 0) >= MAX_LINE_BYTES) {
                throw new ProtocolException("RESP integer line is too long");
            }

            if (b == '-' && digits == 0 && !negative) {
                negative = true;
            } else if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
                digits++;
                if (value > Integer.MAX_VALUE) {
                    throw new ProtocolException("RESP integer out of range");
                }
            } else {
                throw new ProtocolException("Invalid RESP integer");
            }
        }
        throw new ProtocolException("Unexpected end of stream");
    }

    private void readCRLF(BufferedInputStream input) throws IOException {
        if (input.read() != '\r' || input.read() != '\n') {
            throw new ProtocolException("Expected CRLF after bulk string");
        }
    }
}
