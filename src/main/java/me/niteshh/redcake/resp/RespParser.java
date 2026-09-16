package me.niteshh.redcake.resp;

import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class RespParser {
    // RESP (REdis Serialization Protocol) is a simple protocol used by Redis for communication between clients and servers.
    // It defines a way to serialize data structures, such as strings, integers, arrays, and bulk strings,
    // into a format that can be transmitted over a network connection.
    // The RespParser class is responsible for parsing RESP-formatted data received from clients
    // and converting it into a more usable format (e.g., a list of strings representing commands and their arguments).

    public List<String> parseCommand(BufferedInputStream input) throws IOException {
        int firstByte = input.read();

        if (firstByte == -1) {
            return null;
        }

        if (firstByte != '*') {
            throw new IOException( "Expected RESP array but received: " + (char) firstByte);
        }

        int numberOfElements = readInteger(input);

        if (numberOfElements < 0) {
            throw new IOException("Invalid number of command elements");
        }

        List<String> command = new ArrayList<>(numberOfElements);

        for (int i = 0; i < numberOfElements; i++) {
            int type = input.read();
            if (type != '$') {
                throw new IOException("Expected bulk string");
            }

            int length = readInteger(input);

            if (length < 0) {
                throw new IOException(
                        "Null bulk string is not valid for command arguments"
                );
            }

            byte[] data = input.readNBytes(length);

            if (data.length != length) {
                throw new IOException("Unexpected end of stream");
            }

            readCRLF(input);

            String argument = new String(data, StandardCharsets.UTF_8);
            command.add(argument);
        }
        return command;
    }

    private int readInteger(BufferedInputStream input) throws IOException {

        StringBuilder number = new StringBuilder();
        int b;

        while ((b = input.read()) != -1) {
            if (b == '\r') {
                int next = input.read();

                if (next != '\n') {
                    throw new IOException("Invalid RESP line ending");
                }
                break;
            }

            number.append((char) b);
        }

        if (number.isEmpty()) {
            throw new IOException("Expected integer but received empty value");
        }

        try {
            return Integer.parseInt(number.toString());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid RESP integer: " + number, e);
        }
    }

    private void readCRLF(BufferedInputStream input) throws IOException {

        int cr = input.read();
        int lf = input.read();

        if (cr != '\r' || lf != '\n') {
            throw new IOException(
                    "Expected CRLF after bulk string"
            );
        }
    }
}