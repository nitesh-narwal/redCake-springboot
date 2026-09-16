package me.niteshh.redcake.resp;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;


class RespParserTest {

    @Test
    void shouldParsePing() throws Exception {

        String request = "*1\r\n" + "$4\r\n" + "PING\r\n";

        BufferedInputStream input =
                new BufferedInputStream(
                        new ByteArrayInputStream(
                                request.getBytes(StandardCharsets.UTF_8)
                        )
                );

        RespParser parser = new RespParser();

        List<String> result = parser.parseCommand(input);

        assertEquals(List.of("PING"), result);
    }


    @Test
    void shouldParseEcho() throws Exception {

        String request = "*2\r\n" + "$4\r\n" + "ECHO\r\n" + "$5\r\n" + "hello\r\n";

        BufferedInputStream input =
                new BufferedInputStream(
                        new ByteArrayInputStream(
                                request.getBytes(StandardCharsets.UTF_8)
                        )
                );

        List<String> result =
                new RespParser().parseCommand(input);

        assertEquals(
                List.of("ECHO", "hello"),
                result
        );
    }

    @Test
    void shouldParseSet() throws Exception {

        String request = "*3\r\n" + "$3\r\n" + "SET\r\n" + "$4\r\n" + "name\r\n" + "$6\r\n" + "Nitesh\r\n";

        BufferedInputStream input =
                new BufferedInputStream(
                        new ByteArrayInputStream(
                                request.getBytes(StandardCharsets.UTF_8)
                        )
                );

        List<String> result =
                new RespParser().parseCommand(input);

        assertEquals(
                List.of("SET", "name", "Nitesh"),
                result
        );
    }

    @Test
    void shouldParseMultipleCommandsFromSameStream() throws Exception {

        String request = "*1\r\n" + "$4\r\n" + "PING\r\n" + "*2\r\n" + "$4\r\n" + "ECHO\r\n" + "$5\r\n" + "hello\r\n";

        BufferedInputStream input = new BufferedInputStream(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)));

        RespParser parser = new RespParser();

        List<String> first = parser.parseCommand(input);
        List<String> second = parser.parseCommand(input);

        assertEquals(List.of("PING"), first);
        assertEquals(List.of("ECHO", "hello"), second);
    }
}
