package me.niteshh.redcake.server;

import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.resp.SimpleString;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * All currently connected clients: hands out connection ids, powers
 * {@code CLIENT LIST/KILL} and fans commands out to {@code MONITOR} sessions.
 */
@Component
public class ClientRegistry {

    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final Set<Session> monitors = ConcurrentHashMap.newKeySet();

    long nextId() {
        return nextId.getAndIncrement();
    }

    void register(Session session) {
        sessions.put(session.id, session);
    }

    void unregister(Session session) {
        sessions.remove(session.id);
        monitors.remove(session);
    }

    /** @return {@code CLIENT LIST} text: one {@code key=value} line per connection */
    String describeAll() {
        long now = System.currentTimeMillis();
        StringBuilder text = new StringBuilder();
        List<Session> sorted = new ArrayList<>(sessions.values());
        sorted.sort((a, b) -> Long.compare(a.id, b.id));
        for (Session s : sorted) {
            text.append(describe(s, now)).append('\n');
        }
        return text.toString();
    }

    /** @return the {@code CLIENT INFO} line of one connection */
    String describe(Session s, long now) {
        return "id=" + s.id
                + " addr=" + s.address
                + " name=" + s.name
                + " user=" + s.user
                + " age=" + (now - s.connectedAtMillis) / 1000
                + " idle=" + (now - s.lastActiveMillis) / 1000
                + " sub=" + s.channels.size()
                + " psub=" + s.patterns.size()
                + " db=0"
                + " cmd=" + s.lastCommand.toLowerCase();
    }

    /**
     * Closes the connections matching an id or an {@code ip:port} address.
     *
     * @return how many were closed
     */
    int kill(String idOrAddress) {
        int killed = 0;
        for (Session s : sessions.values()) {
            if (idOrAddress.equals(Long.toString(s.id)) || idOrAddress.equals(s.address)) {
                s.close();
                killed++;
            }
        }
        return killed;
    }

    /** @return number of registered connections */
    int count() {
        return sessions.size();
    }

    void addMonitor(Session session) {
        monitors.add(session);
    }

    boolean hasMonitors() {
        return !monitors.isEmpty();
    }

    /** Sends one executed command to every {@code MONITOR} session, Redis-style. */
    void feedMonitors(Session from, List<String> command) {
        StringBuilder line = new StringBuilder();
        line.append(String.format("%.6f", System.currentTimeMillis() / 1000.0))
                .append(" [0 ").append(from.address).append("]");
        for (String word : command) {
            line.append(" \"").append(word.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        RespValue message = new SimpleString(line.toString());
        for (Session monitor : monitors) {
            if (monitor == from) {
                continue;
            }
            try {
                monitor.push(message);
            } catch (IOException e) {
                monitors.remove(monitor);
            }
        }
    }
}
