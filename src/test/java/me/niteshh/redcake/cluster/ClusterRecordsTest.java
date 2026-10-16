package me.niteshh.redcake.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The small data formats that live in znodes and next to the AOF. */
class ClusterRecordsTest {

    @Test
    void nodeRecordSurvivesARoundTrip() {
        NodeRecord original = new NodeRecord("node-1", "10.1.2.3", 6380, 50, NodeRecord.Role.FOLLOWER,
                "ab".repeat(20), 123_456, 7, true, true, false, 42, 8, "9f3a", 1_700_000_000_000L);

        NodeRecord copy = NodeRecord.parse(original.toBytes());

        assertEquals(original, copy);
        assertEquals("10.1.2.3:6380", copy.address());
    }

    @Test
    void theFormatIsReadableInZkCli() {
        String text = new String(new LeaderRecord("n1", "h", 1, 3, "i", 5).toBytes(), StandardCharsets.UTF_8);

        assertTrue(text.contains("node=n1\n"), text);
        assertTrue(text.contains("epoch=3\n"), text);
    }

    @Test
    void unknownFieldsAreIgnoredSoNodesOfDifferentVersionsInteroperate() {
        byte[] future = ("node=n1\nhost=h\nport=1\nrole=leader\nbrand-new-field=whatever\n").getBytes(StandardCharsets.UTF_8);

        NodeRecord record = NodeRecord.parse(future);

        assertEquals("n1", record.nodeId());
        assertEquals(NodeRecord.Role.LEADER, record.role());
        assertEquals(-1, record.dataEpoch(), "missing numbers fall back to 'unknown'");
    }

    @Test
    void anUnknownRoleIsTreatedAsACandidate() {
        NodeRecord record = NodeRecord.parse("node=n\nhost=h\nport=1\nrole=emperor\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(NodeRecord.Role.CANDIDATE, record.role());
    }

    @Test
    void garbageIsRejectedInsteadOfTrustedBlindly() {
        assertThrows(IllegalArgumentException.class, () -> NodeRecord.parse("nonsense".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> NodeRecord.parse("node=n\nhost=h\nport=abc\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aValueWithALineBreakCannotSmuggleInAnotherField() {
        NodeRecord evil = new NodeRecord("n", "h\nrole=leader", 1, 1, NodeRecord.Role.CANDIDATE, "", 0, -1, false,
                false, false, 0, -1, "", 0);

        assertThrows(IllegalArgumentException.class, evil::toBytes);
    }

    @Test
    void leaderRecordAndFailoverHintRoundTrip() {
        LeaderRecord leader = new LeaderRecord("n1", "10.0.0.1", 6379, 12, "inc", 99);
        assertEquals(leader, LeaderRecord.parse(leader.toBytes()));

        FailoverHint hint = new FailoverHint("n2", 12, 1_000);
        assertEquals(hint, FailoverHint.parse(hint.toBytes()));
        assertTrue(hint.hasTarget());
        assertFalse(hint.isExpired(999));
        assertTrue(hint.isExpired(1_000));
        assertFalse(new FailoverHint("", 1, 5).hasTarget());
    }

    @Test
    void sameStateIgnoresOffsetAndTimestamp() {
        NodeRecord a = new NodeRecord("n", "h", 1, 100, NodeRecord.Role.FOLLOWER, "r", 10, 3, false, false, false, 5, 3, "i", 1);
        NodeRecord moved = new NodeRecord("n", "h", 1, 100, NodeRecord.Role.FOLLOWER, "r", 99, 3, false, false, false, 6, 3, "i", 2);
        NodeRecord promoted = new NodeRecord("n", "h", 1, 100, NodeRecord.Role.LEADER, "r", 10, 3, false, false, false, 5, 3, "i", 1);

        assertTrue(a.sameStateAs(moved));
        assertFalse(a.sameStateAs(promoted));
        assertFalse(a.sameStateAs(null));
    }

    @Test
    void theEpochFileLivesNextToTheAofAndIsReplacedAtomically(@TempDir Path dir) throws Exception {
        Path aof = dir.resolve("data").resolve("redcake.aof");
        Path file = ClusterEpochFile.forAof(aof);
        assertEquals("redcake.aof.epoch", file.getFileName().toString());

        assertEquals(-1, ClusterEpochFile.read(file), "no file means unknown");

        ClusterEpochFile.write(file, 4);
        assertEquals(4, ClusterEpochFile.read(file));
        ClusterEpochFile.write(file, 11);
        assertEquals(11, ClusterEpochFile.read(file));
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count(), "no temporary file is left behind");
        }
    }

    @Test
    void anUnreadableEpochFileMeansUnknownNotZero(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("redcake.aof.epoch");
        Files.writeString(file, "not a number");

        assertEquals(-1, ClusterEpochFile.read(file));
    }
}
