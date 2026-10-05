package me.niteshh.redcake.replication;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationBacklogTest {

    private static byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    @Test
    void servesAnyOffsetThatIsStillInTheRing() {
        ReplicationBacklog backlog = new ReplicationBacklog(16, 100);
        backlog.append(bytes("abcdef"));
        backlog.append(bytes("ghij"));

        assertEquals(100, backlog.startOffset());
        assertEquals(110, backlog.endOffset());
        assertArrayEquals(bytes("abcdefghij"), backlog.slice(100));
        assertArrayEquals(bytes("ghij"), backlog.slice(106));
        assertArrayEquals(new byte[0], backlog.slice(110), "caught-up replica gets nothing");
    }

    @Test
    void forgetsTheOldestBytesWhenFullAndRefusesOutdatedOffsets() {
        ReplicationBacklog backlog = new ReplicationBacklog(8, 0);
        backlog.append(bytes("01234567"));
        backlog.append(bytes("89AB")); // overwrites 0123

        assertEquals(4, backlog.startOffset());
        assertEquals(12, backlog.endOffset());
        assertFalse(backlog.canServe(3), "offset 3 was overwritten");
        assertTrue(backlog.canServe(4));
        assertArrayEquals(bytes("456789AB"), backlog.slice(4), "slice must handle ring wrap-around");
        assertFalse(backlog.canServe(13), "an offset beyond the end is impossible");
        assertThrows(IllegalArgumentException.class, () -> backlog.slice(3));
    }

    @Test
    void anAppendLargerThanTheRingKeepsOnlyItsTail() {
        ReplicationBacklog backlog = new ReplicationBacklog(4, 0);
        backlog.append(bytes("abcdefghij"));

        assertEquals(10, backlog.endOffset());
        assertEquals(6, backlog.startOffset());
        assertArrayEquals(bytes("ghij"), backlog.slice(6));
    }

    @Test
    void resetStartsANewHistoryAtAnOffset() {
        ReplicationBacklog backlog = new ReplicationBacklog(8, 0);
        backlog.append(bytes("abc"));
        backlog.reset(50);

        assertEquals(50, backlog.startOffset());
        assertEquals(50, backlog.endOffset());
        assertFalse(backlog.canServe(3));
        assertTrue(backlog.canServe(50));
    }
}
