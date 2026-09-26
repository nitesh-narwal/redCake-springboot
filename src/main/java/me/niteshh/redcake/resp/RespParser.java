package me.niteshh.redcake.resp;

import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class RespParser {

    private static final int MAX_COMMAND_ELEMENTS = 128;
    private static final int MAX_BULK_STRING_BYTES = 1024 * 1024;
    private static final int MAX_LINE_BYTES = 64;

    public List<String> parseCommand(
            BufferedInputStream input
    ) throws IOException {
        int firstByte = input.read();
        if (firstByte == -1) {
            return null;
        }

        if (firstByte != '*') {
            throw new IOException(
                    "Expected RESP array but received: "
                            + (char) firstByte
            );
        }

        int numberOfElements = readInteger(input);
        if (numberOfElements < 1
                || numberOfElements > MAX_COMMAND_ELEMENTS) {
            throw new IOException(
                    "Command must contain between 1 and "
                            + MAX_COMMAND_ELEMENTS + " elements"
            );
        }

        List<String> command = new ArrayList<>(numberOfElements);

        for (int i = 0; i < numberOfElements; i++) {
            if (input.read() != '$') {
                throw new IOException("Expected bulk string");
            }

            int length = readInteger(input);
            if (length < 0 || length > MAX_BULK_STRING_BYTES) {
                throw new IOException(
                        "Bulk string length is invalid or exceeds "
                                + MAX_BULK_STRING_BYTES + " bytes"
                );
            }

            byte[] data = input.readNBytes(length);
            if (data.length != length) {
                throw new IOException("Unexpected end of stream");
            }

            readCRLF(input);
            command.add(new String(data, StandardCharsets.UTF_8));
        }

        return command;
    }

    private int readInteger(
            BufferedInputStream input
    ) throws IOException {
        StringBuilder number = new StringBuilder(16);
        int b;

        while ((b = input.read()) != -1) {
            if (b == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("Invalid RESP line ending");
                }
                break;
            }

            if (number.length() >= MAX_LINE_BYTES) {
                throw new IOException("RESP integer line is too long");
            }

            number.append((char) b);
        }

        if (number.isEmpty()) {
            throw new IOException("Expected integer but received empty value");
        }

        try {
            return Integer.parseInt(number.toString());
        } catch (NumberFormatException e) {
            throw new IOException(
                    "Invalid RESP integer: " + number,
                    e
            );
        }
    }

    private void readCRLF(
            BufferedInputStream input
    ) throws IOException {
        if (input.read() != '\r' || input.read() != '\n') {
            throw new IOException("Expected CRLF after bulk string");
        }
    }
}
