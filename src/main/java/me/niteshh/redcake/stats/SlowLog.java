package me.niteshh.redcake.stats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Remembers the slowest recent commands ({@code SLOWLOG GET}), the first tool
 * to reach for when a production instance feels slow.
 *
 * <p>A command is logged when its execution time reaches
 * {@link #getThresholdMicros()} (default 10 000 us = 10 ms; 0 logs every
 * command; negative disables). Only the newest {@link #getMaxLength()}
 * entries are kept. Arguments are truncated so a huge value cannot bloat
 * the log, and {@code AUTH} is never recorded. Created as a bean by
 * {@code RedCakeConfiguration} so the startup threshold can be applied.
 */
public class SlowLog {

    /** One slow command. */
    public record Entry(
            long id,
            long timestampSeconds,
            long micros,
            List<String> arguments,
            String clientAddress,
            String clientName
    ) {
    }

    private static final int MAX_ARGS = 32;
    private static final int MAX_ARG_LENGTH = 128;

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final AtomicLong nextId = new AtomicLong();
    private volatile long thresholdMicros = 10_000;
    private volatile int maxLength = 128;

    public long getThresholdMicros() {
        return thresholdMicros;
    }

    public void setThresholdMicros(long micros) {
        this.thresholdMicros = micros;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int length) {
        this.maxLength = Math.max(0, length);
    }

    /** Logs {@code command} if it was slow enough. */
    public void record(List<String> command, long micros, String clientAddress, String clientName) {
        long threshold = thresholdMicros;
        if (threshold < 0 || micros < threshold || maxLength == 0) {
            return;
        }

        List<String> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(command.size(), MAX_ARGS); i++) {
            String word = command.get(i);
            shown.add(word.length() > MAX_ARG_LENGTH
                    ? word.substring(0, MAX_ARG_LENGTH) + "... (" + (word.length() - MAX_ARG_LENGTH) + " more bytes)"
                    : word);
        }
        if (command.size() > MAX_ARGS) {
            shown.add("... (" + (command.size() - MAX_ARGS) + " more arguments)");
        }

        Entry entry = new Entry(nextId.getAndIncrement(), System.currentTimeMillis() / 1000,
                micros, shown, clientAddress, clientName);
        synchronized (entries) {
            entries.addFirst(entry);
            while (entries.size() > maxLength) {
                entries.removeLast();
            }
        }
    }

    /** @return up to {@code count} newest entries, newest first */
    public List<Entry> get(int count) {
        synchronized (entries) {
            return entries.stream().limit(count).toList();
        }
    }

    public int length() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public void reset() {
        synchronized (entries) {
            entries.clear();
        }
    }
}
