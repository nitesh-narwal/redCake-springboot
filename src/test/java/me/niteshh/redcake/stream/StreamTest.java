package me.niteshh.redcake.stream;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules of a stream and its groups with a clock the test controls: every time that matters is passed in, so
 * "pending for a minute" needs no sleeping.
 */
class StreamTest {

    private static final long T0 = 1_000_000;

    private static StreamId id(long ms, long seq) {
        return new StreamId(ms, seq);
    }

    private static List<String> kv(String... pairs) {
        return Arrays.asList(pairs);
    }

    private static StreamId add(Stream stream, long ms, long seq, String... pairs) {
        return stream.add(id(ms, seq), true, kv(pairs), 0, T0);
    }

    private static Stream streamWith(int entries) {
        Stream stream = new Stream();
        for (int i = 1; i <= entries; i++) {
            add(stream, i, 0, "n", Integer.toString(i));
        }
        return stream;
    }

    private static void assertConsistent(Stream stream) {
        assertEquals(stream.recountBytes(), stream.estimatedBytes(), "the incremental memory estimate");
        assertEquals(stream.recountLength(), stream.length(), "the entry count");
        StreamId first = stream.firstEntryId();
        assertEquals(first == null ? StreamId.ZERO : first, stream.firstId(), "the recorded first id");
        for (ConsumerGroup group : stream.groups()) {
            int owned = 0;
            for (Consumer consumer : group.consumers()) {
                owned += consumer.pendingCount();
                for (var held : consumer.pending().entrySet()) {
                    assertSame(consumer, group.pending().get(held.getKey()).consumer(),
                            "a pending entry is in its owner's list and the group's");
                }
            }
            assertEquals(group.pendingCount(), owned, "every pending entry has exactly one owner");
        }
    }

    // ---------------------------------------------------------------------------------------- ids

    @Test
    void anAutomaticIdIsTheClockOrOnePastTheLastWhenTheClockStandsStillOrGoesBack() {
        Stream stream = new Stream();
        assertEquals(id(1000, 0), stream.add(null, true, kv("a", "1"), 0, 1000));
        assertEquals(id(1000, 1), stream.add(null, true, kv("a", "2"), 0, 1000), "same millisecond: next sequence");
        assertEquals(id(1000, 2), stream.add(null, true, kv("a", "3"), 0, 900), "the clock went back: never goes back");
        assertEquals(id(1001, 0), stream.add(null, true, kv("a", "4"), 0, 1001));
    }

    @Test
    void aMillisecondsOnlyIdChoosesTheSequence() {
        Stream stream = new Stream();
        assertEquals(id(5, 0), stream.add(id(5, 0), false, kv("a", "1"), 0, T0), "5-* in a new millisecond starts at 0");
        assertEquals(id(5, 1), stream.add(id(5, 0), false, kv("a", "2"), 0, T0));
        assertEquals(id(5, 2), stream.add(id(5, 0), false, kv("a", "3"), 0, T0));
        assertEquals(id(6, 0), stream.add(id(6, 0), false, kv("a", "4"), 0, T0));
        StreamException refused = assertThrows(StreamException.class, () -> stream.add(id(5, 0), false, kv("a", "5"), 0, T0));
        assertTrue(refused.getMessage().contains("equal or smaller"), refused.getMessage());
    }

    @Test
    void idsMustGrowAndNothingChangesWhenOneIsRefused() {
        Stream stream = streamWith(3);
        long bytes = stream.estimatedBytes();
        for (StreamId bad : List.of(id(3, 0), id(2, 99), id(0, 1), StreamId.ZERO)) {
            StreamException refused = assertThrows(StreamException.class, () -> stream.add(bad, true, kv("x", "y"), 0, T0));
            assertTrue(refused.getMessage().contains("equal or smaller than the target stream top item"), refused.getMessage());
        }
        assertEquals(3, stream.length());
        assertEquals(3, stream.entriesAdded());
        assertEquals(id(3, 0), stream.lastId());
        assertEquals(bytes, stream.estimatedBytes());
    }

    @Test
    void whenTheLastIdIsTheLargestPossibleTheStreamIsFull() {
        Stream stream = new Stream();
        stream.add(StreamId.MAX, true, kv("a", "1"), 0, T0);
        StreamException refused = assertThrows(StreamException.class, () -> stream.add(null, true, kv("a", "2"), 0, T0));
        assertTrue(refused.getMessage().contains("exhausted"), refused.getMessage());
        assertEquals("18446744073709551615-18446744073709551615", stream.lastId().toString());
    }

    @Test
    void nextIdNeverReturnsOneThatIsNotLargerThanTheLast() {
        assertThrows(StreamException.class, () -> Stream.nextId(id(5, -1L), id(5, 0), false, T0),
                "the sequence of 5 is used up");
        assertEquals(id(6, 0), Stream.nextId(id(5, -1L), null, true, 5), "the clock cannot help, so the next id carries over");
        assertThrows(StreamException.class, () -> Stream.nextId(StreamId.MAX, null, true, 5));
    }

    // ------------------------------------------------------------------------------- deleting, trimming

    @Test
    void deletingTracksTheFirstEntryAndTheLargestDeletedId() {
        Stream stream = streamWith(5);
        assertEquals(2, stream.delete(List.of(id(1, 0), id(2, 0), id(9, 9))));
        assertEquals(id(3, 0), stream.firstId(), "the first entry is gone: the next one is the first");
        assertEquals(id(2, 0), stream.maxDeletedId());
        assertEquals(1, stream.delete(List.of(id(4, 0))));
        assertEquals(id(3, 0), stream.firstId());
        assertEquals(id(4, 0), stream.maxDeletedId());
        assertEquals(0, stream.delete(List.of(id(4, 0))));
        assertEquals(2, stream.delete(List.of(id(3, 0), id(5, 0))));
        assertEquals(StreamId.ZERO, stream.firstId(), "empty: no first entry");
        assertEquals(5, stream.entriesAdded(), "deleting does not take back what was added");
        assertEquals(id(5, 0), stream.lastId(), "an empty stream still knows its last id");
        assertConsistent(stream);
    }

    @Test
    void trimmingDoesNotCountAsDeleting() {
        Stream stream = streamWith(6);
        assertEquals(3, stream.trimByLength(3, 0));
        assertEquals(id(4, 0), stream.firstId());
        assertEquals(StreamId.ZERO, stream.maxDeletedId(), "only XDEL moves the largest deleted id");
        assertEquals(1, stream.trimByMinId(id(5, 0), 0));
        assertEquals(id(5, 0), stream.firstId());
        assertEquals(2, stream.trimByLength(0, 0));
        assertEquals(StreamId.ZERO, stream.firstId());
        assertEquals(6, stream.entriesAdded());
        assertConsistent(stream);
    }

    @Test
    void setIdChecksBeforeItChangesAnything() {
        Stream stream = streamWith(3);
        stream.delete(List.of(id(3, 0)));
        assertThrows(StreamException.class, () -> stream.setId(id(1, 0), -1, StreamId.ZERO), "below the top entry");
        assertThrows(StreamException.class, () -> stream.setId(id(2, 0), 1, StreamId.ZERO), "fewer added than there are");
        assertThrows(StreamException.class, () -> stream.setId(id(2, 0), -1, StreamId.ZERO), "below the largest deleted id");
        assertEquals(id(3, 0), stream.lastId());
        assertEquals(3, stream.entriesAdded());
        stream.setId(id(10, 0), 50, id(7, 0));
        assertEquals(id(10, 0), stream.lastId());
        assertEquals(50, stream.entriesAdded());
        assertEquals(id(7, 0), stream.maxDeletedId());
        stream.setId(id(10, 0), -1, StreamId.ZERO);
        assertEquals(50, stream.entriesAdded(), "-1 and 0-0 leave the counters alone");
        assertEquals(id(7, 0), stream.maxDeletedId());
    }

    // ----------------------------------------------------------------------------------------- groups

    @Test
    void aGroupHandsOutEachEntryOnceAndTracksWhoHasIt() {
        Stream stream = streamWith(5);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        assertNull(stream.createGroup("g", StreamId.ZERO, 0), "names are unique");
        Consumer alice = stream.createConsumer(group, "alice", T0);
        Consumer bob = stream.createConsumer(group, "bob", T0);
        assertNull(stream.createConsumer(group, "alice", T0));

        Stream.DeliveryResult first = stream.deliverNew(group, alice, 2, false, T0 + 10);
        assertEquals(List.of(id(1, 0), id(2, 0)), first.deliveries().stream().map(d -> d.entry().id()).toList());
        assertTrue(first.positionMoved());
        assertEquals(id(2, 0), group.lastId());
        assertEquals(2, group.entriesRead());

        Stream.DeliveryResult second = stream.deliverNew(group, bob, 0, false, T0 + 20);
        assertEquals(3, second.deliveries().size(), "no count: everything that is left");
        assertEquals(id(5, 0), group.lastId());
        assertEquals(2, alice.pendingCount());
        assertEquals(3, bob.pendingCount());
        assertEquals(5, group.pendingCount());
        assertFalse(stream.hasUndelivered(group));
        assertTrue(stream.deliverNew(group, bob, 0, false, T0 + 30).deliveries().isEmpty());
        assertFalse(stream.deliverNew(group, bob, 0, false, T0 + 30).positionMoved());

        add(stream, 6, 0, "n", "6");
        assertTrue(stream.hasUndelivered(group));
        assertEquals(1, stream.lag(group));
        assertConsistent(stream);
    }

    @Test
    void noAckMovesTheGroupButLeavesNothingPending() {
        Stream stream = streamWith(3);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        Consumer reader = stream.createConsumer(group, "r", T0);
        Stream.DeliveryResult result = stream.deliverNew(group, reader, 0, true, T0);
        assertEquals(3, result.deliveries().size());
        assertEquals(0, group.pendingCount());
        assertEquals(id(3, 0), group.lastId());
        assertEquals(-1L, reader.activeTime(), "nothing was handed over to be worked on");
        assertConsistent(stream);
    }

    @Test
    void historyReturnsTheConsumersOwnEntriesAndCountsAnotherDelivery() {
        Stream stream = streamWith(4);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        Consumer reader = stream.createConsumer(group, "r", T0);
        stream.deliverNew(group, reader, 0, false, T0);
        stream.delete(List.of(id(2, 0)));

        List<Stream.HistoryItem> history = stream.deliverHistory(reader, id(0, 1), 0, T0 + 500);
        assertEquals(4, history.size());
        assertNull(history.get(1).entry(), "deleted from the stream but still pending: reported without fields");
        assertEquals(2, history.get(0).deliveryCount());
        assertEquals(T0 + 500, history.get(0).deliveryTime());
        assertEquals(1, history.get(1).deliveryCount(), "an entry that is gone is not counted as delivered");
        assertEquals(1, stream.deliverHistory(reader, id(3, 0), 1, T0 + 600).size());
        assertEquals(3, reader.pending().get(id(3, 0)).deliveryCount());
        assertEquals(0, stream.deliverHistory(reader, id(5, 0), 0, T0).size());
        assertConsistent(stream);
    }

    @Test
    void ackForgetsPendingEntriesEverywhere() {
        Stream stream = streamWith(3);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        Consumer reader = stream.createConsumer(group, "r", T0);
        stream.deliverNew(group, reader, 0, false, T0);
        assertEquals(2, stream.ack(group, List.of(id(1, 0), id(3, 0), id(9, 9), id(1, 0))));
        assertEquals(1, group.pendingCount());
        assertEquals(1, reader.pendingCount());
        assertEquals(0, stream.ack(group, List.of(id(1, 0))));
        assertConsistent(stream);
    }

    @Test
    void settingTheGroupBackHandsEntriesOutAgainAndTakesThemOverFromTheirOwner() {
        Stream stream = streamWith(3);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        Consumer a = stream.createConsumer(group, "a", T0);
        Consumer b = stream.createConsumer(group, "b", T0);
        stream.deliverNew(group, a, 0, false, T0);
        stream.setGroupPosition(group, StreamId.ZERO, 0);
        stream.deliverNew(group, b, 2, false, T0 + 5);
        assertEquals(2, b.pendingCount());
        assertEquals(1, a.pendingCount(), "the two re-delivered entries moved to b");
        assertEquals(1, b.pending().get(id(1, 0)).deliveryCount(), "a fresh delivery: the count starts over");
        assertEquals(3, group.pendingCount());
        assertConsistent(stream);
    }

    @Test
    void deletingAConsumerForgetsItsPendingEntries() {
        Stream stream = streamWith(4);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        Consumer a = stream.createConsumer(group, "a", T0);
        Consumer b = stream.createConsumer(group, "b", T0);
        stream.deliverNew(group, a, 3, false, T0);
        stream.deliverNew(group, b, 0, false, T0);
        assertEquals(3, stream.deleteConsumer(group, a));
        assertEquals(1, group.pendingCount());
        assertNull(group.consumer("a"));
        assertConsistent(stream);
        assertTrue(stream.destroyGroup("g"));
        assertFalse(stream.destroyGroup("g"));
        assertEquals(stream.recountBytes(), stream.estimatedBytes());
    }

    // -------------------------------------------------------------------------------- claim, autoclaim

    private static Stream.ClaimOptions claim(long minIdle, long time, long retry, boolean force, boolean justId) {
        return new Stream.ClaimOptions(minIdle, time, retry, force, justId, StreamId.ZERO);
    }

    @Test
    void claimTakesOverOnlyWhatHasBeenPendingLongEnough() {
        Stream stream = streamWith(3);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        Consumer a = stream.createConsumer(group, "a", T0);
        stream.deliverNew(group, a, 1, false, T0);             // entry 1 at T0
        stream.deliverNew(group, a, 1, false, T0 + 40_000);    // entry 2 at T0 + 40 s

        long now = T0 + 60_000;
        Stream.ClaimResult result = stream.claim(group, "b", List.of(id(1, 0), id(2, 0), id(3, 0)),
                claim(30_000, -1, -1, false, false), now);
        assertEquals(1, result.claimed().size(), "entry 1 was idle for 60 s, entry 2 for 20 s, entry 3 is not pending");
        assertEquals(id(1, 0), result.claimed().getFirst().id());
        assertEquals(2, result.claimed().getFirst().deliveryCount(), "a claim counts as a delivery");
        assertEquals(now, result.claimed().getFirst().deliveryTime());
        assertTrue(result.consumerCreated());
        assertEquals(1, group.consumer("b").pendingCount());
        assertEquals(1, a.pendingCount());
        assertConsistent(stream);

        Stream.ClaimResult justIds = stream.claim(group, "b", List.of(id(2, 0)), claim(0, -1, -1, false, true), now);
        assertNull(justIds.claimed().getFirst().entry(), "ids only");
        assertEquals(1, justIds.claimed().getFirst().deliveryCount(), "JUSTID does not count a delivery");
        assertFalse(justIds.consumerCreated());
    }

    @Test
    void claimOptionsSetTheTimeAndTheCountAndForceCreatesPendingEntries() {
        Stream stream = streamWith(3);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        long now = T0 + 1000;
        Stream.ClaimResult forced = stream.claim(group, "b", List.of(id(2, 0), id(9, 0)),
                claim(0, T0 + 5, 7, true, true), now);
        assertEquals(1, forced.claimed().size(), "FORCE needs the message to exist");
        assertEquals(T0 + 5, forced.claimed().getFirst().deliveryTime());
        assertEquals(7, forced.claimed().getFirst().deliveryCount());
        assertEquals(1, group.pendingCount());

        Stream.ClaimResult clamped = stream.claim(group, "b", List.of(id(2, 0)), claim(0, now + 99_999, -1, false, true), now);
        assertEquals(now, clamped.claimed().getFirst().deliveryTime(), "a time in the future is replaced by now");
        Stream.ClaimResult negative = stream.claim(group, "b", List.of(id(2, 0)), claim(0, -5, -1, false, true), now);
        assertEquals(now, negative.claimed().getFirst().deliveryTime());
        assertConsistent(stream);
    }

    @Test
    void claimingAMessageThatWasDeletedDropsItsPendingEntry() {
        Stream stream = streamWith(2);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        Consumer a = stream.createConsumer(group, "a", T0);
        stream.deliverNew(group, a, 0, false, T0);
        stream.delete(List.of(id(1, 0)));
        Stream.ClaimResult result = stream.claim(group, "b", List.of(id(1, 0), id(2, 0)), claim(0, -1, -1, false, false), T0 + 10);
        assertEquals(List.of(id(1, 0)), result.dropped());
        assertEquals(List.of(id(2, 0)), result.claimed().stream().map(Stream.Claimed::id).toList());
        assertEquals(1, group.pendingCount());
        assertConsistent(stream);
    }

    @Test
    void claimRaisesTheGroupPositionWithLastId() {
        Stream stream = streamWith(5);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        Stream.ClaimResult result = stream.claim(group, "b", List.of(),
                new Stream.ClaimOptions(0, -1, -1, false, false, id(4, 0)), T0);
        assertTrue(result.positionMoved());
        assertEquals(id(4, 0), group.lastId());
        assertFalse(stream.claim(group, "b", List.of(), new Stream.ClaimOptions(0, -1, -1, false, false, id(2, 0)), T0).positionMoved(),
                "LASTID never moves the group back");
        assertEquals(id(4, 0), group.lastId());
    }

    @Test
    void autoClaimWalksThePendingListAndReportsWhereToContinue() {
        Stream stream = streamWith(6);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        Consumer a = stream.createConsumer(group, "a", T0);
        stream.deliverNew(group, a, 0, false, T0);
        long now = T0 + 100_000;

        Stream.AutoClaimResult page = stream.autoClaim(group, "b", 50_000, StreamId.ZERO, 2, false, now);
        assertEquals(List.of(id(1, 0), id(2, 0)), page.claimed().stream().map(Stream.Claimed::id).toList());
        assertEquals(id(3, 0), page.next(), "continue with the first entry not looked at");
        assertTrue(page.consumerCreated());

        Stream.AutoClaimResult rest = stream.autoClaim(group, "b", 50_000, page.next(), 100, true, now);
        assertEquals(4, rest.claimed().size());
        assertEquals(StreamId.ZERO, rest.next(), "0-0: the whole list has been looked at");
        assertEquals(6, group.consumer("b").pendingCount());
        assertEquals(0, a.pendingCount());
        assertEquals(2, group.pending().get(id(1, 0)).deliveryCount());
        assertEquals(1, group.pending().get(id(6, 0)).deliveryCount(), "JUSTID: not counted");
        assertConsistent(stream);
    }

    @Test
    void autoClaimDropsDeletedMessagesAndSkipsTheNotIdleEnough() {
        Stream stream = streamWith(4);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        Consumer a = stream.createConsumer(group, "a", T0);
        stream.deliverNew(group, a, 2, false, T0);
        stream.deliverNew(group, a, 0, false, T0 + 90_000);
        stream.delete(List.of(id(1, 0)));
        Stream.AutoClaimResult result = stream.autoClaim(group, "b", 60_000, StreamId.ZERO, 10, false, T0 + 100_000);
        assertEquals(List.of(id(1, 0)), result.deleted());
        assertEquals(List.of(id(2, 0)), result.claimed().stream().map(Stream.Claimed::id).toList(),
                "3 and 4 were delivered only 10 s ago");
        assertEquals(3, group.consumer("a").pendingCount() + group.consumer("b").pendingCount());
        assertConsistent(stream);
    }

    @Test
    void autoClaimLooksAtAtMostTenTimesCountEntries() {
        Stream stream = streamWith(60);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        Consumer a = stream.createConsumer(group, "a", T0);
        stream.deliverNew(group, a, 0, false, T0);
        Stream.AutoClaimResult result = stream.autoClaim(group, "b", 999_999_999L, StreamId.ZERO, 2, false, T0 + 1);
        assertTrue(result.claimed().isEmpty(), "nothing is idle that long");
        assertEquals(id(21, 0), result.next(), "20 entries looked at, then it stops and says where");
    }

    @Test
    void restorePendingPutsAnEntryBackWithoutAskingWhetherTheMessageExists() {
        Stream stream = streamWith(2);
        ConsumerGroup group = stream.createGroup("g", id(9, 0), 5);
        stream.restorePending(group, "c", id(1, 0), T0 + 11, 3, T0);
        stream.restorePending(group, "c", id(99, 0), T0 + 22, 4, T0);   // no such message
        stream.restorePending(group, "d", id(1, 0), T0 + 33, 5, T0);    // taken over
        assertEquals(2, group.pendingCount());
        assertEquals(1, group.consumer("d").pendingCount());
        assertEquals(1, group.consumer("c").pendingCount());
        Pending moved = group.pending().get(id(1, 0));
        assertEquals("d", moved.consumer().name());
        assertEquals(T0 + 33, moved.deliveryTime());
        assertEquals(5, moved.deliveryCount());
        assertConsistent(stream);
    }

    // ------------------------------------------------------------------------- lag and the read counter

    @Test
    void lagFollowsTheReadCounterUntilDeletionsMakeItUnknown() {
        Stream stream = streamWith(4);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        Consumer reader = stream.createConsumer(group, "r", T0);
        assertEquals(4, stream.lag(group));
        stream.deliverNew(group, reader, 1, true, T0);
        assertEquals(3, stream.lag(group));
        stream.delete(List.of(id(3, 0)));
        assertEquals(-1, stream.lag(group), "a deleted entry ahead of the group: the count cannot be trusted");
        stream.deliverNew(group, reader, 0, true, T0);
        assertEquals(0, stream.lag(group), "caught up");
        add(stream, 5, 0, "n", "5");
        assertEquals(1, stream.lag(group));
    }

    // ------------------------------------------------------------------------------ snapshot and copy

    @Test
    void aCopyDoesNotSeeLaterChanges() {
        Stream stream = streamWith(5);
        ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
        Consumer reader = stream.createConsumer(group, "r", T0);
        stream.deliverNew(group, reader, 2, false, T0);
        Stream copy = stream.copy();
        assertConsistent(copy);

        stream.delete(List.of(id(1, 0)));
        stream.ack(group, List.of(id(2, 0)));
        stream.deliverNew(group, reader, 0, false, T0 + 1);
        stream.destroyGroup("g");
        add(stream, 6, 0, "n", "6");

        assertEquals(5, copy.length());
        assertEquals(id(5, 0), copy.lastId());
        ConsumerGroup frozen = copy.group("g");
        assertEquals(id(2, 0), frozen.lastId());
        assertEquals(2, frozen.pendingCount());
        assertEquals(2, frozen.consumer("r").pendingCount());
    }

    @Test
    void theSnapshotCommandsStartWithTheEntriesThenTheCountersThenTheGroups() {
        Stream stream = streamWith(3);
        stream.delete(List.of(id(2, 0)));
        ConsumerGroup group = stream.createGroup("g", id(1, 0), 1);
        Consumer reader = stream.createConsumer(group, "r", T0);
        stream.restorePending(group, "r", id(3, 0), T0 + 7, 2, T0);
        stream.createConsumer(group, "idle", T0);

        List<List<String>> commands = new ArrayList<>();
        stream.copy().snapshotCommands("k").forEachRemaining(commands::add);
        assertEquals(List.of("XADD", "k", "1-0", "n", "1"), commands.get(0));
        assertEquals(List.of("XADD", "k", "3-0", "n", "3"), commands.get(1));
        assertEquals(List.of("XSETID", "k", "3-0", "ENTRIESADDED", "3", "MAXDELETEDID", "2-0"), commands.get(2));
        assertEquals(List.of("XGROUP", "CREATE", "k", "g", "1-0", "ENTRIESREAD", "1"), commands.get(3));
        assertEquals(List.of("XGROUP", "CREATECONSUMER", "k", "g", "idle"), commands.get(4), "consumers by name");
        assertEquals(List.of("XGROUP", "CREATECONSUMER", "k", "g", "r"), commands.get(5));
        assertEquals(List.of("XRESTORE", "k", "g", "r", "3-0", Long.toString(T0 + 7), "2"), commands.get(6));
        assertEquals(7, commands.size());
        assertNotNull(reader);
    }

    @Test
    void anEmptyStreamIsRecreatedWithTheTrick() {
        Stream stream = streamWith(2);
        stream.delete(List.of(id(1, 0), id(2, 0)));
        List<List<String>> commands = new ArrayList<>();
        stream.copy().snapshotCommands("k").forEachRemaining(commands::add);
        assertEquals(List.of("XADD", "k", "MAXLEN", "0", "1-1", "x", "y"), commands.get(0));
        assertEquals(List.of("XSETID", "k", "2-0", "ENTRIESADDED", "2", "MAXDELETEDID", "2-0"), commands.get(1));
        assertEquals(2, commands.size());
    }

    // ---------------------------------------------------------------------------------- random testing

    @Test
    void randomOperationsKeepTheBookkeepingConsistent() {
        for (long seed = 1; seed <= 30; seed++) {
            Random random = new Random(seed);
            Stream stream = new Stream();
            ConsumerGroup group = stream.createGroup("g", StreamId.ZERO, 0);
            long now = T0;
            long ms = 1;
            for (int step = 0; step < 1200; step++) {
                now += random.nextInt(50);
                String context = "seed " + seed + " step " + step;
                int op = random.nextInt(100);
                String name = "c" + random.nextInt(4);
                if (op < 30) {
                    ms += random.nextInt(2);
                    stream.add(null, true, kv("n", Integer.toString(step), "x", "y".repeat(random.nextInt(20))), 0, ms);
                } else if (op < 45) {
                    Consumer consumer = group.consumer(name);
                    consumer = consumer != null ? consumer : stream.createConsumer(group, name, now);
                    stream.deliverNew(group, consumer, random.nextInt(4), random.nextInt(6) == 0, now);
                } else if (op < 52) {
                    Consumer consumer = group.consumer(name);
                    if (consumer != null) {
                        stream.deliverHistory(consumer, StreamId.ZERO, random.nextInt(4), now);
                    }
                } else if (op < 62 && group.pendingCount() > 0) {
                    List<StreamId> some = new ArrayList<>(group.pending().keySet());
                    stream.ack(group, some.subList(0, 1 + random.nextInt(Math.min(3, some.size()))));
                } else if (op < 70) {
                    List<StreamId> ids = new ArrayList<>(group.pending().keySet());
                    stream.claim(group, name, ids.subList(0, Math.min(ids.size(), 3)),
                            claim(random.nextInt(100), -1, random.nextBoolean() ? 5 : -1, random.nextBoolean(), random.nextBoolean()), now);
                } else if (op < 76) {
                    stream.autoClaim(group, name, random.nextInt(200), StreamId.ZERO, 1 + random.nextInt(3), random.nextBoolean(), now);
                } else if (op < 84 && stream.length() > 0) {
                    List<StreamEntry> some = stream.range(StreamId.ZERO, StreamId.MAX, false, 1 + random.nextInt(4));
                    stream.delete(some.stream().map(StreamEntry::id).toList());
                } else if (op < 88) {
                    stream.trimByLength(random.nextInt(8), 0);
                } else if (op < 91 && group.consumer(name) != null) {
                    stream.deleteConsumer(group, group.consumer(name));
                } else if (op < 94) {
                    stream.setGroupPosition(group, random.nextBoolean() ? StreamId.ZERO : stream.lastId(), random.nextBoolean() ? 0 : -1);
                } else if (op < 96) {
                    stream.restorePending(group, name, new StreamId(1 + random.nextInt(50), 0), now, 1 + random.nextInt(4), now);
                } else {
                    stream.lag(group);
                    stream.pendingSummary(group);
                }
                if (step % 20 == 0) {
                    assertConsistent(stream);
                }
            }
            assertConsistent(stream);
            Stream copy = stream.copy();
            assertConsistent(copy);
            assertEquals(stream.length(), copy.length(), "seed " + seed);
        }
    }
}
