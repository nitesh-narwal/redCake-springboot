package me.niteshh.redcake.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Sorted set: unique members ordered by (score, member).
 *
 * <p>A {@link HashMap} gives O(1) score lookup and a {@link TreeSet} keeps
 * the order, so add/remove are O(log n) and range scans are linear in the
 * result size. Rank queries walk the tree (O(n)) - fine for moderate sets.
 *
 * <p>Not thread-safe by itself: the store only touches it inside the key's
 * atomic {@code compute} section.
 */
public final class ZSet {

    /** One (score, member) pair. */
    public record Entry(double score, String member) implements Comparable<Entry> {
        @Override
        public int compareTo(Entry other) {
            int byScore = Double.compare(score, other.score);
            return byScore != 0 ? byScore : member.compareTo(other.member);
        }
    }

    private final Map<String, Double> scores = new HashMap<>();
    private final TreeSet<Entry> ordered = new TreeSet<>();

    /** @return number of members */
    public int size() {
        return scores.size();
    }

    /** @return the member's score or {@code null} */
    public Double score(String member) {
        return scores.get(member);
    }

    /**
     * Inserts or re-scores a member.
     *
     * @return {@code true} if the member is new
     */
    public boolean put(String member, double score) {
        Double previous = scores.put(member, score);
        if (previous != null) {
            ordered.remove(new Entry(previous, member));
        }
        ordered.add(new Entry(score, member));
        return previous == null;
    }

    /** @return {@code true} if the member existed */
    public boolean remove(String member) {
        Double previous = scores.remove(member);
        if (previous == null) {
            return false;
        }
        ordered.remove(new Entry(previous, member));
        return true;
    }

    /** @return 0-based rank in ascending order, or -1 */
    public int rank(String member) {
        Double score = scores.get(member);
        if (score == null) {
            return -1;
        }
        return ordered.headSet(new Entry(score, member), false).size();
    }

    /** @return all entries in ascending order (a copy) */
    public List<Entry> toList() {
        return new ArrayList<>(ordered);
    }

    /** @return a deep copy, used for consistent snapshots */
    public ZSet copy() {
        ZSet copy = new ZSet();
        ordered.forEach(e -> copy.put(e.member(), e.score()));
        return copy;
    }

    /** Removes and returns the lowest-scored entry, or {@code null} if empty. */
    public Entry pollFirst() {
        Entry first = ordered.pollFirst();
        if (first != null) {
            scores.remove(first.member());
        }
        return first;
    }

    /** Removes and returns the highest-scored entry, or {@code null} if empty. */
    public Entry pollLast() {
        Entry last = ordered.pollLast();
        if (last != null) {
            scores.remove(last.member());
        }
        return last;
    }

    /** Formats a score like Redis: integers without ".0", {@code inf}/{@code -inf}. */
    public static String formatScore(double score) {
        if (Double.isInfinite(score)) {
            return score > 0 ? "inf" : "-inf";
        }
        if (score == Math.rint(score) && Math.abs(score) < 1e17) {
            return Long.toString((long) score);
        }
        return Double.toString(score);
    }

    /** Parses a score ({@code inf}, {@code -inf}, {@code +inf} accepted). */
    public static double parseScore(String text) {
        return switch (text.toLowerCase(java.util.Locale.ROOT)) {
            case "inf", "+inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> {
                double value = Double.parseDouble(text);
                if (Double.isNaN(value)) {
                    throw new NumberFormatException("NaN");
                }
                yield value;
            }
        };
    }
}
