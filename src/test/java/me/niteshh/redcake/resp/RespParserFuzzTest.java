package me.niteshh.redcake.resp;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Randomised robustness test for the one component that parses untrusted
 * bytes. Whatever the input, the parser may only return commands or throw an
 * {@link IOException}; it must never throw another exception type, hang, or
 * allocate based on a lying length prefix. (Seeded, so failures reproduce.
 * For deeper coverage run a coverage-guided fuzzer such as Jazzer.)
 */
class RespParserFuzzTest {

    private static final byte[] VALID = ("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n"
            + "*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n").getBytes(StandardCharsets.ISO_8859_1);

    private static void parseEverything(byte[] input, boolean inline) {
        RespParser parser = new RespParser();
        BufferedInputStream in = new BufferedInputStream(new ByteArrayInputStream(input));
        try {
            for (int i = 0; i < 100; i++) {
                List<String> command = inline ? parser.parseClientCommand(in) : parser.parseCommand(in);
                if (command == null) {
                    return;
                }
            }
        } catch (IOException expected) {
            // malformed input is allowed to fail this way
        } catch (Throwable t) {
            fail("Parser threw " + t + " for input " + java.util.HexFormat.of().formatHex(input));
        }
    }

    @Test
    void randomBytesNeverCrashTheParser() {
        Random random = new Random(1234);
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            for (int i = 0; i < 20_000; i++) {
                byte[] input = new byte[random.nextInt(200)];
                random.nextBytes(input);
                parseEverything(input, false);
                parseEverything(input, true);
            }
        });
    }

    @Test
    void mutatedValidRequestsNeverCrashTheParser() {
        Random random = new Random(99);
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            for (int i = 0; i < 30_000; i++) {
                byte[] input = VALID.clone();
                int mutations = 1 + random.nextInt(4);
                for (int m = 0; m < mutations; m++) {
                    input[random.nextInt(input.length)] = (byte) random.nextInt(256);
                }
                if (random.nextInt(4) == 0) { // truncate: torn read
                    input = java.util.Arrays.copyOf(input, random.nextInt(input.length));
                }
                parseEverything(input, false);
                parseEverything(input, true);
            }
        });
    }

    @Test
    void hugeDeclaredLengthsAreRejectedWithoutAllocating() {
        for (String header : List.of("*2147483647\r\n", "*1\r\n$2147483647\r\n", "*1\r\n$-5\r\n",
                "*1\r\n$99999999999999999999\r\n", "*99999999999999999999\r\n")) {
            parseEverything(header.getBytes(StandardCharsets.ISO_8859_1), false);
        }
    }
}
