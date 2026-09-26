package me.niteshh.redcake.resp;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@Component
public class RespWriter {

    public void write(RespValue value, OutputStream outputStream) throws IOException {
        // Implementation for writing RESP values to the output stream
        switch(value) {
            case SimpleString simpleString -> writeSimpleString(simpleString, outputStream);
            case BulkString bulkString -> writeBulkString(bulkString, outputStream);
            case IntegerValue integerValue -> writeIntegerValue(integerValue, outputStream);
            case ErrorValue errorValue -> writeErrorValue(errorValue, outputStream);
            case NullValue ignored -> writeNullValue(outputStream);
        }
    }

    private void writeNullValue(OutputStream outputStream) throws IOException {
        outputStream.write("$-1\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private void writeErrorValue(
            ErrorValue errorValue,
            OutputStream outputStream
    ) throws IOException {
        String message = errorValue.message()
                .replace('\r', ' ')
                .replace('\n', ' ');
        String response = "-" + message + "\r\n";
        outputStream.write(response.getBytes(StandardCharsets.UTF_8));
    }

    private void writeIntegerValue(IntegerValue integerValue, OutputStream outputStream) throws IOException {
        String response = ":" + integerValue.value() + "\r\n";
        outputStream.write(response.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBulkString(BulkString bulkString, OutputStream outputStream) throws IOException {
        byte[] data = bulkString.value().getBytes(StandardCharsets.UTF_8);
        String header = "$" + data.length + "\r\n";
        outputStream.write(header.getBytes(StandardCharsets.UTF_8));
        outputStream.write(data);
        outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void writeSimpleString(SimpleString simpleString, OutputStream outputStream) throws IOException {
        String response = "+" + simpleString.value() + "\r\n";
        outputStream.write(response.getBytes(StandardCharsets.UTF_8));
    }
}
