package me.niteshh.redcake.stream;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The entry log against a {@code TreeMap}: thousands of random appends, deletes, trims and range reads, across
 * block boundaries, with the incremental size estimate recounted after every step.
 */
class EntryLogTest {

    private static List<String> fields(String... pairs) {
        return Arrays.asList(pairs);
    }

    private static void append(EntryLog log, TreeMap<StreamId, String[]> model, long ms, long seq, String... pairs) {
        log.append(ms, seq, fields(pairs), 0, pairs.length / 2);
        model.put(new StreamId(ms, seq), pairs);
    }

    private static void assertSame(EntryLog log, TreeMap<StreamId, String[]> model, String context) {
        assertEquals(model.size(), log.size(), context + ": size");
        assertEquals(model.size(), log.recountSize(), context + ": slots that hold an entry");
        assertEquals(log.recountBytes(), log.bytes(), context + ": the incremental memory estimate");
        assertEquals(model.isEmpty() ? null : model.firstKey(), log.first(), context + ": first");
        assertEquals(model.isEmpty() ? null : model.lastKey(), log.last(), context + ": last");
        Iterator<StreamEntry> all = log.iterator();
        for (Map.Entry<StreamId, String[]> expected : model.entrySet()) {
            assertTrue(all.hasNext(), context + ": iterator ended early");
            StreamEntry entry = all.next();
            assertEquals(expected.getKey(), entry.id(), context);
            assertArrayEquals(expected.getValue(), entry.fields(), context + ": fields of " + entry.id());
        }
        assertFalse(all.hasNext(), context + ": iterator has more");
    }

    private static void assertRange(EntryLog log, TreeMap<StreamId, String[]> model, StreamId from, StreamId to,
                                    boolean reverse, long limit, String context) {
        List<StreamEntry> got = log.range(from, to, reverse, limit);
        NavigableMap<StreamId, String[]> inside = from.compareTo(to) > 0
                ? new TreeMap<>() : model.subMap(from, true, to, true);
        List<StreamId> want = new ArrayList<>(reverse ? inside.descendingKeySet() : inside.navigableKeySet());
        if (limit > 0 && want.size() > limit) {
            want = want.subList(0, (int) limit);
        }
        List<StreamId> gotIds = got.stream().map(StreamEntry::id).toList();
        assertEquals(want, gotIds, context + ": range " + from + ".." + to + (reverse ? " reversed" : "") + " limit " + limit);
        for (StreamEntry entry : got) {
            assertArrayEquals(model.get(entry.id()), entry.fields(), context);
        }
    }

    @Test
    void anEmptyLogHasNothingToShow() {
        EntryLog log = new EntryLog();
        assertEquals(0, log.size());
        assertNull(log.first());
        assertNull(log.last());
        assertTrue(log.range(StreamId.ZERO, StreamId.MAX, false, 0).isEmpty());
        assertNull(log.get(1, 1));
        assertFalse(log.contains(1, 1));
        assertFalse(log.delete(1, 1));
        assertEquals(0, log.trimToLength(0, 0));
        assertEquals(0, log.trimBefore(StreamId.MAX, 0));
        assertFalse(log.iterator().hasNext());
    }

    @Test
    void entriesWithTheSameFieldNamesShareThem() {
        EntryLog shared = new EntryLog();
        EntryLog distinct = new EntryLog();
        for (int i = 0; i < 100; i++) {
            shared.append(1, i, fields("task", "t-" + i, "payload", "data"), 0, 2);
            distinct.append(1, i, fields("task" + i, "t-" + i, "payload" + i, "data"), 0, 2);
        }
        assertTrue(shared.bytes() < distinct.bytes(),
                "names stored once per block: " + shared.bytes() + " vs " + distinct.bytes());
        StreamEntry entry = shared.get(1, 42);
        assertArrayEquals(new String[]{"task", "t-42", "payload", "data"}, entry.fields());
        StreamEntry other = distinct.get(1, 42);
        assertArrayEquals(new String[]{"task42", "t-42", "payload42", "data"}, other.fields());
    }

    @Test
    void anEntryWithOtherNamesThanItsBlockKeepsItsOwn() {
        EntryLog log = new EntryLog();
        TreeMap<StreamId, String[]> model = new TreeMap<>();
        append(log, model, 1, 0, "a", "1", "b", "2");
        append(log, model, 2, 0, "b", "3", "a", "4");       // same names, other order
        append(log, model, 3, 0, "a", "5");                 // fewer
        append(log, model, 4, 0, "a", "6", "b", "7", "c", "8");
        append(log, model, 5, 0, "a", "", "b", "");         // empty values
        append(log, model, 6, 0, "", "x");                  // an empty field name
        assertSame(log, model, "mixed shapes");
    }

    @Test
    void binaryValuesAndLongValuesRoundTrip() {
        EntryLog log = new EntryLog();
        TreeMap<StreamId, String[]> model = new TreeMap<>();
        StringBuilder bytes = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            bytes.append((char) i); // every byte value, including 0, \r, \n and the high half
        }
        String big = "x".repeat(300_000); // a length that needs a three-byte varint
        append(log, model, 1, 0, "bin", bytes.toString(), "big", big);
        assertSame(log, model, "binary");
        assertEquals(256, log.get(1, 0).fields()[1].length());
        assertEquals(big, log.get(1, 0).fields()[3]);
    }

    @Test
    void lookupsAndRangesAcrossBlocks() {
        EntryLog log = new EntryLog();
        TreeMap<StreamId, String[]> model = new TreeMap<>();
        for (int i = 0; i < 1000; i++) {
            append(log, model, 10 + i / 3, i % 3, "n", Integer.toString(i));
        }
        assertTrue(log.blockCount() >= 1000 / EntryLog.BLOCK_MAX, "entries are spread over blocks");
        assertSame(log, model, "after appending");
        for (StreamId id : model.keySet()) {
            assertTrue(log.contains(id.ms(), id.seq()));
            assertArrayEquals(model.get(id), log.get(id.ms(), id.seq()).fields());
        }
        assertFalse(log.contains(9, 0));
        assertFalse(log.contains(10, 3));
        assertFalse(log.contains(100000, 0));
        assertNull(log.get(10, 3));
        assertRange(log, model, StreamId.ZERO, StreamId.MAX, false, 0, "all");
        assertRange(log, model, StreamId.ZERO, StreamId.MAX, true, 0, "all reversed");
        assertRange(log, model, new StreamId(50, 0), new StreamId(60, 1), false, 0, "inner");
        assertRange(log, model, new StreamId(50, 0), new StreamId(60, 1), true, 7, "inner reversed, limited");
        assertRange(log, model, new StreamId(0, 0), new StreamId(9, 5), false, 0, "before everything");
        assertRange(log, model, new StreamId(5000, 0), StreamId.MAX, false, 0, "after everything");
        assertRange(log, model, new StreamId(60, 1), new StreamId(50, 0), false, 0, "start after end");
    }

    @Test
    void trimmingByLengthKeepsTheNewestEntries() {
        EntryLog log = new EntryLog();
        TreeMap<StreamId, String[]> model = new TreeMap<>();
        for (int i = 0; i < 700; i++) {
            append(log, model, 1, i, "n", Integer.toString(i));
        }
        assertEquals(0, log.trimToLength(700, 0), "already short enough");
        assertEquals(200, log.trimToLength(500, 0));
        for (int i = 0; i < 200; i++) {
            model.remove(new StreamId(1, i));
        }
        assertSame(log, model, "trimmed to 500");
        assertEquals(10, log.trimToLength(0, 10), "a limit stops it");
        for (int i = 200; i < 210; i++) {
            model.remove(new StreamId(1, i));
        }
        assertSame(log, model, "limited trim");
        assertEquals(490, log.trimToLength(0, 0));
        model.clear();
        assertSame(log, model, "everything trimmed");
        assertEquals(0, log.blockCount());
        assertEquals(0, log.bytes(), "no blocks, no bytes");
    }

    @Test
    void trimmingByIdRemovesWhatIsOlder() {
        EntryLog log = new EntryLog();
        TreeMap<StreamId, String[]> model = new TreeMap<>();
        for (int i = 0; i < 700; i++) {
            append(log, model, 100 + i, 0, "n", Integer.toString(i));
        }
        assertEquals(0, log.trimBefore(new StreamId(100, 0), 0), "nothing is older");
        assertEquals(250, log.trimBefore(new StreamId(350, 0), 0));
        model.headMap(new StreamId(350, 0)).clear();
        assertSame(log, model, "before 350");
        assertEquals(20, log.trimBefore(new StreamId(1000, 0), 20), "a limit");
        for (int i = 0; i < 20; i++) {
            model.remove(model.firstKey());
        }
        assertSame(log, model, "limited");
        assertEquals(430, log.trimBefore(StreamId.MAX, 0));
        model.clear();
        assertSame(log, model, "all");
    }

    @Test
    void deletingLeavesHolesThatAreCompactedAndEmptyBlocksAreDropped() {
        EntryLog log = new EntryLog();
        TreeMap<StreamId, String[]> model = new TreeMap<>();
        for (int i = 0; i < 600; i++) {
            append(log, model, 1, i, "n", Integer.toString(i));
        }
        int blocks = log.blockCount();
        for (int i = 0; i < 600; i++) {
            if (i % 5 != 0) {
                assertTrue(log.delete(1, i));
                model.remove(new StreamId(1, i));
            }
        }
        assertFalse(log.delete(1, 1), "already gone");
        assertFalse(log.delete(2, 2), "never there");
        assertSame(log, model, "four of five deleted");
        assertEquals(120, log.size());
        for (int i = 0; i < 600; i += 5) {
            assertTrue(log.delete(1, i));
            model.remove(new StreamId(1, i));
        }
        assertSame(log, model, "all deleted");
        assertEquals(0, log.blockCount(), "blocks without entries are dropped");
        assertTrue(blocks > 0);

        append(log, model, 2, 0, "n", "again");
        assertSame(log, model, "usable after being emptied");
    }

    @Test
    void aCopyIsIndependentOfTheOriginal() {
        EntryLog log = new EntryLog();
        TreeMap<StreamId, String[]> model = new TreeMap<>();
        for (int i = 0; i < 300; i++) {
            append(log, model, 1, i, "n", Integer.toString(i));
        }
        log.delete(1, 7);
        model.remove(new StreamId(1, 7));
        EntryLog copy = log.copy();
        TreeMap<StreamId, String[]> frozen = new TreeMap<>(model);
        for (int i = 0; i < 100; i++) {
            log.delete(1, 100 + i);
            model.remove(new StreamId(1, 100 + i));
        }
        log.append(2, 0, fields("n", "new"), 0, 1);
        assertSame(copy, frozen, "the copy did not change");
        assertEquals(copy.recountBytes(), copy.bytes());
    }

    @Test
    void randomOperationsAgreeWithAModel() {
        for (long seed = 1; seed <= 40; seed++) {
            Random random = new Random(seed);
            EntryLog log = new EntryLog();
            TreeMap<StreamId, String[]> model = new TreeMap<>();
            long ms = 1;
            long seq = 0;
            for (int step = 0; step < 1500; step++) {
                String context = "seed " + seed + " step " + step;
                int op = random.nextInt(100);
                if (op < 55) {
                    if (random.nextInt(4) == 0) {
                        ms += 1 + random.nextInt(3);
                        seq = 0;
                    } else {
                        seq++;
                    }
                    int pairs = 1 + random.nextInt(3);
                    String[] kv = new String[pairs * 2];
                    boolean usual = random.nextInt(5) != 0;
                    for (int i = 0; i < pairs; i++) {
                        kv[2 * i] = usual ? "f" + i : "g" + random.nextInt(3);
                        kv[2 * i + 1] = "v".repeat(random.nextInt(30)) + random.nextInt(1000);
                    }
                    append(log, model, ms, seq, kv);
                } else if (op < 75 && !model.isEmpty()) {
                    StreamId victim = random.nextBoolean()
                            ? randomKey(model, random) : new StreamId(random.nextInt(50), random.nextInt(5));
                    assertEquals(model.remove(victim) != null, log.delete(victim.ms(), victim.seq()), context);
                } else if (op < 80 && !model.isEmpty()) {
                    long keep = random.nextInt(model.size() + 3);
                    long limit = random.nextBoolean() ? 0 : 1 + random.nextInt(30);
                    long want = Math.min(Math.max(0, model.size() - keep), limit == 0 ? Long.MAX_VALUE : limit);
                    assertEquals(want, log.trimToLength(keep, limit), context + ": trimToLength " + keep + " limit " + limit);
                    for (long i = 0; i < want; i++) {
                        model.remove(model.firstKey());
                    }
                } else if (op < 85 && !model.isEmpty()) {
                    StreamId min = random.nextBoolean() ? randomKey(model, random) : new StreamId(random.nextInt(ms > 1 ? (int) ms : 2), 0);
                    long limit = random.nextBoolean() ? 0 : 1 + random.nextInt(30);
                    long want = 0;
                    for (StreamId id : model.headMap(min).keySet()) {
                        if (limit != 0 && want >= limit) {
                            break;
                        }
                        want++;
                    }
                    assertEquals(want, log.trimBefore(min, limit), context + ": trimBefore " + min + " limit " + limit);
                    for (long i = 0; i < want; i++) {
                        model.remove(model.firstKey());
                    }
                } else {
                    StreamId a = new StreamId(random.nextInt((int) ms + 2), random.nextInt(6));
                    StreamId b = new StreamId(random.nextInt((int) ms + 2), random.nextInt(6));
                    assertRange(log, model, a, b, random.nextBoolean(), random.nextInt(3) == 0 ? random.nextInt(20) : 0, context);
                    StreamId probe = model.isEmpty() ? a : randomKey(model, random);
                    assertEquals(model.containsKey(probe), log.contains(probe.ms(), probe.seq()), context);
                }
                if (step % 25 == 0 || step == 1499) {
                    assertSame(log, model, context);
                }
            }
        }
    }

    private static StreamId randomKey(TreeMap<StreamId, String[]> model, Random random) {
        List<StreamId> keys = new ArrayList<>(model.keySet());
        return keys.get(random.nextInt(keys.size()));
    }
}
