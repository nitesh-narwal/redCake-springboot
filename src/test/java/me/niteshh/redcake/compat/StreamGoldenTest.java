package me.niteshh.redcake.compat;

import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stream commands against what a real Redis 7.4.2 answered.
 *
 * <p>{@code streams/scenarios.txt} holds command sequences (one command per line, words split at blanks);
 * {@code streams/redis-7.4.2.golden} holds, for each command, the reply that <em>Redis</em> gave, in the format of
 * {@link RespClient}. The test replays the scenarios against RedCake and compares every reply, error texts included
 * - there are about 450 commands, covering ids, ranges, trimming, groups, pending lists, claiming, {@code lag}.
 * That needs no Redis installation: the answers were recorded once.
 *
 * <p>Lines that end in {@code @t} have clock-dependent numbers (idle times, delivery times); those are replaced by
 * {@code _} on both sides before comparing. {@code sleep N} waits N seconds. {@code MAXLEN ~} is absent on purpose:
 * RedCake trims exactly where Redis stops at a node boundary.
 *
 * <p>To record again (after adding scenarios, or for another Redis version):
 * <pre>mvn test -Dtest=StreamGoldenTest -Dredcake.golden.record=127.0.0.1:6379</pre> (and rename the file after the version)
 */
class StreamGoldenTest {

    private static final String SCENARIOS = "src/test/resources/streams/scenarios.txt";
    private static final String GOLDEN = "src/test/resources/streams/redis-7.4.2.golden";

    private static final List<Pattern> CLOCK = List.of(
            Pattern.compile(":\\d{12,}"),                                               // a time in epoch milliseconds
            Pattern.compile("(\\[\"[^\"]*\", \"[^\"]*\", ):\\d+(, :\\d+\\])"),          // XPENDING row: the idle time
            Pattern.compile("(\"idle\", ):\\d+"),                                       // XINFO CONSUMERS
            Pattern.compile("(\"inactive\", ):(?!-1\\b)\\d+"));

    /** A scenario command and what to do with its reply. */
    private record Step(String line, boolean clock) {
        List<String> words() {
            return List.of(line.split(" +"));
        }
    }

    private record Scenario(String name, List<Step> steps) {
    }

    private static List<Scenario> scenarios() throws IOException {
        List<Scenario> out = new ArrayList<>();
        Scenario current = null;
        List<Step> steps = null;
        for (String raw : Files.readAllLines(Path.of(SCENARIOS), StandardCharsets.ISO_8859_1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("## ")) {
                steps = new ArrayList<>();
                current = new Scenario(line.substring(3), steps);
                out.add(current);
            } else {
                boolean clock = line.endsWith(" @t");
                steps.add(new Step(clock ? line.substring(0, line.length() - 3) : line, clock));
            }
        }
        return out;
    }

    private static String masked(String reply, boolean clock) {
        if (!clock || reply == null) {
            return reply;
        }
        String result = reply;
        result = CLOCK.get(0).matcher(result).replaceAll(":_");
        result = CLOCK.get(1).matcher(result).replaceAll("$1:_$2");
        result = CLOCK.get(2).matcher(result).replaceAll("$1_");
        result = CLOCK.get(3).matcher(result).replaceAll("$1_");
        return result;
    }

    /** Runs every scenario on {@code client}; the result is the golden text. */
    private static List<String> run(RespClient client, List<Scenario> scenarios) throws Exception {
        List<String> out = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            out.add("## " + scenario.name());
            client.call("FLUSHALL");
            for (Step step : scenario.steps()) {
                List<String> words = step.words();
                if (words.getFirst().equalsIgnoreCase("sleep")) {
                    Thread.sleep((long) (Double.parseDouble(words.get(1)) * 1000));
                    out.add("> " + step.line());
                    continue;
                }
                String reply = client.call(words.toArray(new String[0]));
                out.add("> " + step.line() + (step.clock() ? " @t" : ""));
                out.add(masked(reply, step.clock()));
            }
        }
        return out;
    }

    @Test
    void redcakeAnswersLikeRedis() throws Exception {
        String record = System.getProperty("redcake.golden.record");
        List<Scenario> scenarios = scenarios();
        try (TestServer server = new TestServer().start(); RespClient client = server.client()) {
            List<String> actual = run(client, scenarios);
            if (record != null) {
                int colon = record.lastIndexOf(':');
                try (RespClient redis = new RespClient(record.substring(0, colon), Integer.parseInt(record.substring(colon + 1)))) {
                    Files.write(Path.of(GOLDEN), run(redis, scenarios), StandardCharsets.ISO_8859_1);
                }
                System.out.println("Recorded " + GOLDEN + " from " + record);
                return;
            }

            List<String> golden = Files.readAllLines(Path.of(GOLDEN), StandardCharsets.ISO_8859_1);
            assertEquals(golden.size(), actual.size(), "same number of lines (commands and replies)");
            List<String> differences = new ArrayList<>();
            String command = "";
            int compared = 0;
            for (int i = 0; i < golden.size(); i++) {
                if (golden.get(i).startsWith("> ") || golden.get(i).startsWith("## ")) {
                    command = golden.get(i);
                    assertEquals(golden.get(i), actual.get(i), "the same script");
                } else {
                    compared++;
                    if (!golden.get(i).equals(actual.get(i))) {
                        differences.add(command + "\n    redis  : " + golden.get(i) + "\n    redcake: " + actual.get(i));
                    }
                }
            }
            assertTrue(compared > 400, "enough commands were compared: " + compared);
            assertTrue(differences.isEmpty(), differences.size() + " of " + compared + " replies differ from Redis:\n"
                    + String.join("\n", differences.subList(0, Math.min(15, differences.size()))));
        }
    }
}
