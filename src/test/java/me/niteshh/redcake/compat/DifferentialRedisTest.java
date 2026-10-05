package me.niteshh.redcake.compat;

import me.niteshh.redcake.support.RespClient;
import me.niteshh.redcake.support.TestServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Differential test: runs the same command scripts against RedCake and a
 * real {@code redis-server} and compares every reply. Redis is the
 * specification, so any difference is a RedCake bug (or a documented gap).
 *
 * <p>Skipped automatically when {@code redis-server} is not on the PATH
 * ({@code sudo apt install redis-server}). Comparison rules:
 * <ul>
 *   <li>Errors compare by code word only ({@code ERR}, {@code WRONGTYPE}, ...);
 *       the human-readable text may differ.</li>
 *   <li>A leading {@code #} sorts array replies first (unordered results such as
 *       {@code SMEMBERS}, {@code KEYS}).</li>
 *   <li>A leading {@code ~} allows a small numeric difference (TTL values that
 *       depend on timing).</li>
 * </ul>
 */
class DifferentialRedisTest {

    private static Process redis;
    private static int redisPort;
    private static TestServer redcake;

    @BeforeAll
    static void startBoth() throws Exception {
        File binary = findOnPath("redis-server");
        assumeTrue(binary != null, "redis-server is not installed; differential test skipped");

        try (ServerSocket socket = new ServerSocket(0)) {
            redisPort = socket.getLocalPort();
        }
        redis = new ProcessBuilder(binary.getPath(), "--port", Integer.toString(redisPort),
                "--bind", "127.0.0.1", "--save", "", "--appendonly", "no")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        for (int attempt = 0; attempt < 50; attempt++) {
            try (RespClient c = new RespClient("127.0.0.1", redisPort)) {
                if ("+PONG".equals(c.call("PING"))) {
                    break;
                }
            } catch (Exception notYet) {
                Thread.sleep(100);
            }
        }
        redcake = new TestServer().start();
    }

    @AfterAll
    static void stopBoth() {
        if (redis != null) {
            redis.destroy();
        }
        if (redcake != null) {
            redcake.close();
        }
    }

    private static File findOnPath(String name) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            File file = new File(dir, name);
            if (file.canExecute()) {
                return file;
            }
        }
        return null;
    }

    private static final List<String[]> SCRIPTS = List.of(
            new String[]{"strings",
                    "SET a 1", "GET a", "SET a 2 NX", "SET b 3 NX", "SET a 4 XX", "SET zz 1 XX", "SET a 5 GET",
                    "SET a 6 EX 100", "~TTL a", "SET a 7 KEEPTTL", "~TTL a", "SET a 8", "TTL a",
                    "SET a x EX 0", "SET a x EX abc", "SET a x NX XX", "SET a x PX", "SET a x BOGUS",
                    "APPEND s hello", "APPEND s world", "GET s", "STRLEN s", "STRLEN nokey",
                    "GETRANGE s 0 4", "GETRANGE s -5 -1", "GETRANGE s 5 2", "GETRANGE nokey 0 3",
                    "SETNX n 1", "SETNX n 2", "GETSET n 9", "GETDEL n", "GET n",
                    "MSET m1 a m2 b", "MGET m1 m2 m3", "MSET m1", "MSETNX x1 a x2 b", "MSETNX x3 c x1 d", "GET x3",
                    "GET", "GET a b", "SET a"},
            new String[]{"counters",
                    "INCR c", "INCR c", "INCRBY c 10", "DECR c", "DECRBY c 5", "INCRBY c abc", "INCRBY c 1.5",
                    "SET big 9223372036854775807", "INCR big", "DECRBY big -1", "SET text abc", "INCR text",
                    "SET spaced ' 1'", "DECRBY c -9223372036854775808"},
            new String[]{"keys",
                    "SET k1 v", "SET k2 v", "SET other v", "#KEYS k*", "#KEYS *", "#KEYS k?", "EXISTS k1 k2 nope k1",
                    "DEL k1 nope", "DBSIZE", "RENAME k2 k3", "RENAME nope x", "GET k3", "TYPE k3", "TYPE nope",
                    "RENAME k3 k3", "UNLINK k3 other", "DBSIZE", "RANDOMKEY", "FLUSHALL", "RANDOMKEY", "DBSIZE"},
            new String[]{"expire",
                    "SET e v", "EXPIRE e 100", "~TTL e", "EXPIRE e 100 NX", "EXPIRE e 200 GT", "EXPIRE e 50 LT",
                    "EXPIRE e 50 XX", "PERSIST e", "TTL e", "PERSIST e", "EXPIRE nope 10", "PEXPIRE e 100000",
                    "~PTTL e", "EXPIRE e 0", "EXISTS e", "SET f v", "EXPIRE f -5", "GET f", "TTL nope", "PTTL nope",
                    "EXPIRE f abc", "EXPIRE f"},
            new String[]{"hashes",
                    "HSET h a 1 b 2", "HSET h a 9", "HGET h a", "HGET h x", "HMGET h a x b", "HGETALL h", "HKEYS h",
                    "HVALS h", "HLEN h", "HEXISTS h a", "HEXISTS h z", "HSETNX h a 5", "HSETNX h c 3",
                    "HINCRBY h a 10", "HINCRBY h c abc", "HSET h t text", "HINCRBY h t 1", "HSTRLEN h t",
                    "HDEL h a b zz", "HLEN h", "HDEL h c t", "EXISTS h", "HGETALL nope", "HSET h f"},
            new String[]{"lists",
                    "RPUSH l a b c", "LPUSH l y x", "LRANGE l 0 -1", "LRANGE l 1 2", "LRANGE l -2 -1", "LRANGE l 10 20",
                    "LLEN l", "LINDEX l 0", "LINDEX l -1", "LINDEX l 99", "LSET l 0 X", "LSET l 99 z", "LSET nolist 0 z",
                    "LPOP l", "RPOP l", "LPOP l 2", "LLEN l", "RPUSH r a b a c a", "LREM r 2 a", "LRANGE r 0 -1",
                    "LREM r -1 a", "LRANGE r 0 -1", "LTRIM r 1 -1", "LRANGE r 0 -1", "LPOP nolist", "LRANGE nolist 0 -1",
                    "RPOP r 5", "EXISTS r"},
            new String[]{"sets",
                    "SADD a 1 2 3", "SADD a 3 4", "SADD b 3 4 5", "SCARD a", "SISMEMBER a 1", "SISMEMBER a 9",
                    "#SMEMBERS a", "#SINTER a b", "#SUNION a b", "#SDIFF a b", "#SINTER a nope", "SREM a 1 2 zz",
                    "#SMEMBERS a", "SREM a 3 4", "EXISTS a", "#SMEMBERS nope"},
            new String[]{"zsets",
                    "ZADD z 1 a 3 c 2 b", "ZADD z 5 a", "ZADD z CH 6 a", "ZADD z NX 9 a", "ZADD z XX 9 nope",
                    "ZRANGE z 0 -1", "ZREVRANGE z 0 -1", "ZRANGE z 0 1 WITHSCORES", "ZSCORE z a", "ZSCORE z nope",
                    "ZRANK z b", "ZRANK z nope", "ZCARD z", "ZINCRBY z 1.5 c", "ZRANGEBYSCORE z -inf 5",
                    "ZRANGEBYSCORE z (2 5", "ZRANGEBYSCORE z (2 5 WITHSCORES", "ZRANGEBYSCORE z 3 +inf LIMIT 0 5",
                    "ZCOUNT z 2 5", "ZPOPMIN z", "ZPOPMAX z", "ZREM z c", "EXISTS z", "ZADD z abc m", "ZADD z 1",
                    "ZRANGE z 0 -1 BOGUS"},
            new String[]{"wrongtype",
                    "SET s v", "HSET h f v", "RPUSH l x", "SADD st x", "ZADD z 1 m",
                    "GET h", "HGET s f", "LPUSH h x", "SADD l x", "ZADD s 1 m", "INCR h", "APPEND l x", "STRLEN h",
                    "LRANGE s 0 -1", "SMEMBERS z", "ZRANGE st 0 -1", "TYPE s", "TYPE h", "TYPE l", "TYPE st", "TYPE z",
                    "MGET s h l", "SET h overwrite", "TYPE h", "DEL l", "EXISTS l"},
            new String[]{"misc",
                    "PING", "PING hello", "ECHO hi", "ECHO", "SELECT 0", "SELECT 1", "NOSUCHCOMMAND", "ECHO a b"}
    );

    @Test
    void redcakeRepliesLikeRealRedisForTheScripts() throws Exception {
        List<String> differences = new ArrayList<>();

        for (String[] script : SCRIPTS) {
            try (RespClient real = new RespClient("127.0.0.1", redisPort);
                 RespClient mine = redcake.client()) {
                real.call("FLUSHALL");
                mine.call("FLUSHALL");

                for (int i = 1; i < script.length; i++) {
                    String line = script[i];
                    boolean sorted = line.startsWith("#");
                    boolean tolerant = line.startsWith("~");
                    String[] words = tokenize(sorted || tolerant ? line.substring(1) : line);

                    String expected = normalize(real.call(words), sorted);
                    String actual = normalize(mine.call(words), sorted);
                    if (!equivalent(expected, actual, tolerant)) {
                        differences.add("[" + script[0] + "] " + line
                                + "\n    redis:   " + expected + "\n    redcake: " + actual);
                    }
                }
            }
        }
        assertTrue(differences.isEmpty(), "RedCake differs from Redis:\n" + String.join("\n", differences));
    }

    /** Splits on spaces; single quotes group a word with spaces ({@code ' 1'}). */
    private static String[] tokenize(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char ch : line.toCharArray()) {
            if (ch == '\'') {
                quoted = !quoted;
            } else if (ch == ' ' && !quoted) {
                if (current.length() > 0) {
                    words.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0) {
            words.add(current.toString());
        }
        return words.toArray(new String[0]);
    }

    /** Errors keep only their code word; unordered arrays are sorted. */
    private static String normalize(String reply, boolean sortArray) {
        if (reply == null) {
            return "<closed>";
        }
        if (reply.startsWith("-")) {
            int space = reply.indexOf(' ');
            return space < 0 ? reply : reply.substring(0, space);
        }
        if (sortArray && reply.startsWith("[") && reply.length() > 2) {
            List<String> items = new ArrayList<>(Arrays.asList(reply.substring(1, reply.length() - 1).split(", ")));
            Collections.sort(items);
            return items.toString();
        }
        return reply;
    }

    private static boolean equivalent(String expected, String actual, boolean tolerant) {
        if (expected.equals(actual)) {
            return true;
        }
        if (tolerant && expected.startsWith(":") && actual.startsWith(":")) {
            long a = Long.parseLong(expected.substring(1));
            long b = Long.parseLong(actual.substring(1));
            return Math.abs(a - b) <= 2_000; // covers TTL (seconds) and PTTL (millis) timing noise
        }
        return false;
    }
}
