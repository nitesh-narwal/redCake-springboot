package me.niteshh.redcake.resp;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


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

    @Test
    void shouldRejectCommandWithTooManyElements() {
        String request = "*1025\r\n";

        assertThrows(
                java.io.IOException.class,
                () -> new RespParser().parseCommand(
                        new BufferedInputStream(
                                new ByteArrayInputStream(
                                        request.getBytes(StandardCharsets.UTF_8)
                                )
                        )
                )
        );
    }

    @Test
    void shouldRejectBulkStringLargerThanConfiguredLimit() {
        String request = "*1\r\n$1048577\r\n";

        assertThrows(
                java.io.IOException.class,
                () -> new RespParser().parseCommand(
                        new BufferedInputStream(
                                new ByteArrayInputStream(
                                        request.getBytes(StandardCharsets.UTF_8)
                                )
                        )
                )
        );
    }

    @Test
    void shouldParseInlineCommandsForTelnetAndNc() throws Exception {
        BufferedInputStream input = new BufferedInputStream(
                new ByteArrayInputStream("\r\nSET  a   b\r\nPING\n".getBytes(StandardCharsets.UTF_8)));
        RespParser parser = new RespParser();

        assertEquals(List.of("SET", "a", "b"), parser.parseClientCommand(input));
        assertEquals(List.of("PING"), parser.parseClientCommand(input));
        org.junit.jupiter.api.Assertions.assertNull(parser.parseClientCommand(input));
    }

    @Test
    void strictParserStillRejectsInlineText() {
        assertThrows(
                ProtocolException.class,
                () -> new RespParser().parseCommand(new BufferedInputStream(
                        new ByteArrayInputStream("PING\r\n".getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    void shouldRejectMalformedFramingWithProtocolException() {
        for (String bad : List.of("*1\r\n$abc\r\n", "*1\r\n$3\r\nabcXX", "*-1\r\n", "*1\r\n+oops\r\n",
                "*1\r\n$99999999999\r\n")) {
            assertThrows(
                    java.io.IOException.class,
                    () -> new RespParser().parseCommand(new BufferedInputStream(
                            new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8)))),
                    bad);
        }
    }

    @Test
    void everyByteValueSurvivesParsingUnchanged() throws Exception {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.writeBytes("*2\r\n$3\r\nSET\r\n$256\r\n".getBytes(StandardCharsets.ISO_8859_1));
        request.writeBytes(all);
        request.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));

        List<String> parsed = new RespParser().parseCommand(
                new BufferedInputStream(new ByteArrayInputStream(request.toByteArray())));

        assertEquals(256, parsed.get(1).length());
        assertArrayEquals(all, parsed.get(1).getBytes(StandardCharsets.ISO_8859_1));
    }
}
