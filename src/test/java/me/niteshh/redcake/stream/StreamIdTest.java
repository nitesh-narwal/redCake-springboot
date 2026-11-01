package me.niteshh.redcake.stream;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ids are two unsigned 64-bit numbers; Java's longs are signed, so every comparison and every print is checked. */
class StreamIdTest {

    @Test
    void idsOrderByMillisecondsThenSequenceAsUnsignedNumbers() {
        StreamId small = new StreamId(1, 5);
        StreamId sameMs = new StreamId(1, 6);
        StreamId later = new StreamId(2, 0);
        assertTrue(small.compareTo(sameMs) < 0);
        assertTrue(sameMs.compareTo(later) < 0);
        assertEquals(0, small.compareTo(new StreamId(1, 5)));

        StreamId huge = new StreamId(-1L, 0); // 18446744073709551615-0: negative as a signed long
        assertTrue(huge.compareTo(later) > 0, "a huge unsigned value is larger, not negative");
        assertTrue(StreamId.MAX.compareTo(huge) > 0);
        assertTrue(StreamId.ZERO.compareTo(small) < 0);
    }

    @Test
    void printsUnsigned() {
        assertEquals("0-0", StreamId.ZERO.toString());
        assertEquals("18446744073709551615-18446744073709551615", StreamId.MAX.toString());
        assertEquals("1793552929636-3", new StreamId(1793552929636L, 3).toString());
        assertEquals("9223372036854775808-0", new StreamId(Long.MIN_VALUE, 0).toString());
    }

    @Test
    void nextAndPreviousStepAcrossTheSequenceAndStopAtTheEnds() {
        assertEquals(new StreamId(5, 1), new StreamId(5, 0).next());
        assertEquals(new StreamId(6, 0), new StreamId(5, -1L).next(), "the sequence rolls over into the milliseconds");
        assertNull(StreamId.MAX.next());

        assertEquals(new StreamId(5, 0), new StreamId(5, 1).previous());
        assertEquals(new StreamId(4, -1L), new StreamId(5, 0).previous());
        assertNull(StreamId.ZERO.previous());
    }

    @Test
    void parsesWhatClientsWrite() {
        assertEquals(new StreamId(5, 0), StreamId.parseExact("5"), "milliseconds only: the first id of that millisecond");
        assertEquals(new StreamId(5, 7), StreamId.parseExact("5-7"));
        assertEquals(new StreamId(7, 8), StreamId.parseExact("007-008"), "leading zeros are fine");
        assertEquals(StreamId.MAX, StreamId.parseExact("18446744073709551615-18446744073709551615"));
        assertEquals(new StreamId(5, -1L), StreamId.parse("5", -1L, false).id(), "as the end of a range: the last id");

        StreamId.Parsed automatic = StreamId.parse("5-*", 0, true);
        assertEquals(new StreamId(5, 0), automatic.id());
        assertFalse(automatic.seqGiven());
        assertTrue(StreamId.parse("5-6", 0, true).seqGiven());
    }

    @Test
    void refusesWhatIsNotAnId() {
        for (String text : List.of("", "-", "+", "*", "a", "5-", "-5", "5-a", "5--5", "5-6-7", " 5", "5 ", "+5",
                "0x5", "1e3", "18446744073709551616", "5-18446744073709551616", "999999999999999999999",
                "5-*", "*-5", "5.5", "٣")) {
            assertNull(StreamId.parseExact(text), "'" + text + "' is not an id");
        }
        assertNull(StreamId.parse("5-**", 0, true));
        assertNull(StreamId.parse("x".repeat(128), 0, false), "longer than 127 characters");
        assertNull(StreamId.parse("1".repeat(21), 0, false), "21 digits cannot fit");
    }

    @Test
    void theLargestValuesSurviveTheRoundTrip() {
        for (long ms : new long[]{0, 1, Long.MAX_VALUE, Long.MIN_VALUE, -1L}) {
            for (long seq : new long[]{0, 1, Long.MAX_VALUE, Long.MIN_VALUE, -1L}) {
                StreamId id = new StreamId(ms, seq);
                assertEquals(id, StreamId.parseExact(id.toString()), id.toString());
            }
        }
    }
}
