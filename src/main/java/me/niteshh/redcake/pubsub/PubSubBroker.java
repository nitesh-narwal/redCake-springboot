package me.niteshh.redcake.pubsub;

import me.niteshh.redcake.resp.ArrayValue;
import me.niteshh.redcake.resp.BulkString;
import me.niteshh.redcake.store.GlobMatcher;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publish/subscribe hub. Messages are fire-and-forget and exist only in
 * memory; they are not persisted and, unlike data writes, not replicated.
 *
 * <p>Registry mutation is lock-free ({@link ConcurrentHashMap} of concurrent
 * sets). {@link #publish} delivers on the publisher's thread: a subscriber
 * that stops reading its socket can therefore slow the publisher down - a
 * deliberate simplicity trade-off (no per-subscriber queues yet).
 */
@Component
public class PubSubBroker {

    private final Map<String, Set<Subscriber>> channels = new ConcurrentHashMap<>();
    private final Map<String, Set<Subscriber>> patterns = new ConcurrentHashMap<>();

    /** Adds {@code subscriber} to an exact channel. */
    public void subscribe(String channel, Subscriber subscriber) {
        channels.computeIfAbsent(channel, c -> ConcurrentHashMap.newKeySet()).add(subscriber);
    }

    /** Adds {@code subscriber} to a glob pattern. */
    public void psubscribe(String pattern, Subscriber subscriber) {
        patterns.computeIfAbsent(pattern, p -> ConcurrentHashMap.newKeySet()).add(subscriber);
    }

    public void unsubscribe(String channel, Subscriber subscriber) {
        remove(channels, channel, subscriber);
    }

    public void punsubscribe(String pattern, Subscriber subscriber) {
        remove(patterns, pattern, subscriber);
    }

    private static void remove(Map<String, Set<Subscriber>> map, String key, Subscriber subscriber) {
        map.computeIfPresent(key, (k, set) -> {
            set.remove(subscriber);
            return set.isEmpty() ? null : set;
        });
    }

    /**
     * Sends {@code message} to every matching subscriber.
     *
     * @return the number of subscribers it was delivered to
     */
    public int publish(String channel, String message) {
        int delivered = 0;

        Set<Subscriber> direct = channels.get(channel);
        if (direct != null) {
            ArrayValue push = new ArrayValue(List.of(
                    new BulkString("message"), new BulkString(channel), new BulkString(message)));
            for (Subscriber subscriber : direct) {
                if (deliver(subscriber, push)) {
                    delivered++;
                }
            }
        }

        for (Map.Entry<String, Set<Subscriber>> entry : patterns.entrySet()) {
            if (!GlobMatcher.matches(entry.getKey(), channel)) {
                continue;
            }
            ArrayValue push = new ArrayValue(List.of(
                    new BulkString("pmessage"), new BulkString(entry.getKey()),
                    new BulkString(channel), new BulkString(message)));
            for (Subscriber subscriber : entry.getValue()) {
                if (deliver(subscriber, push)) {
                    delivered++;
                }
            }
        }
        return delivered;
    }

    private boolean deliver(Subscriber subscriber, ArrayValue message) {
        try {
            subscriber.push(message);
            return true;
        } catch (IOException e) {
            return false; // the connection is dying; its own thread will clean up
        }
    }

    /** @return active channel names matching {@code pattern} ({@code null} = all) */
    public List<String> channelNames(String pattern) {
        List<String> names = new ArrayList<>();
        for (String channel : channels.keySet()) {
            if (pattern == null || GlobMatcher.matches(pattern, channel)) {
                names.add(channel);
            }
        }
        return names;
    }

    /** @return number of subscribers of one channel */
    public int subscriberCount(String channel) {
        Set<Subscriber> set = channels.get(channel);
        return set == null ? 0 : set.size();
    }

    /** @return number of distinct active patterns */
    public int patternCount() {
        return patterns.size();
    }
}
