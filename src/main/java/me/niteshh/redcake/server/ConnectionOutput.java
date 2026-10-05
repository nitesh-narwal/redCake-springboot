package me.niteshh.redcake.server;

import me.niteshh.redcake.resp.RespValue;
import me.niteshh.redcake.resp.RespWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The buffered write side of one client connection.
 *
 * <p>Normally only the connection's own thread writes, but Pub/Sub and
 * {@code MONITOR} deliver messages from <em>other</em> threads. The lock
 * makes every {@link #write}/{@link #push} an indivisible unit, so a pushed
 * message can never end up in the middle of a command reply. (A
 * {@code ReentrantLock} instead of {@code synchronized} so virtual threads do
 * not pin their carrier while blocked on a slow socket.)
 */
final class ConnectionOutput {

    private final OutputStream out;
    private final RespWriter writer;
    private final ReentrantLock lock = new ReentrantLock();

    ConnectionOutput(OutputStream bufferedOut, RespWriter writer) {
        this.out = bufferedOut;
        this.writer = writer;
    }

    /** Buffers one reply; call {@link #flush()} when no more replies are pending. */
    void write(RespValue value) throws IOException {
        lock.lock();
        try {
            writer.write(value, out);
        } finally {
            lock.unlock();
        }
    }

    void flush() throws IOException {
        lock.lock();
        try {
            out.flush();
        } finally {
            lock.unlock();
        }
    }

    /** Writes and flushes immediately (unsolicited message from another thread). */
    void push(RespValue value) throws IOException {
        lock.lock();
        try {
            writer.write(value, out);
            out.flush();
        } finally {
            lock.unlock();
        }
    }
}
