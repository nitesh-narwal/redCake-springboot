package me.niteshh.redcake.replication.primary;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The primary's end of one replica link.
 *
 * <h3>Why it has its own writer thread</h3>
 * Writing to a slow replica's socket can block for a long time. If that write
 * happened inside {@link #send} - which the primary calls while holding the
 * global write lock - one slow replica would freeze every client write.
 * Therefore {@link #send} <b>never touches the network</b>: it only appends
 * the bytes to an in-memory queue (bounded to {@value #MAX_PENDING_BYTES}
 * bytes) and wakes a dedicated virtual-thread writer that drains the queue in
 * batches (one flush per batch, which also improves throughput).
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li><b>Snapshot phase</b> - the snapshot thread streams the initial
 *       dataset with {@link #writeSnapshotCommand}. Live writes that arrive
 *       meanwhile are queued, not written.</li>
 *   <li>{@link #finishSnapshot} flushes the snapshot and starts the writer,
 *       which sends the queued writes in order, then every later write.</li>
 *   <li>If the queue exceeds the cap (replica too slow) or a write fails the
 *       connection is closed and the failure listener unregisters it - the
 *       primary sacrifices the replica rather than its own memory.</li>
 * </ol>
 */
@Slf4j
public class ReplicaConnection {

    /** Queue cap per replica. */
    private static final long MAX_PENDING_BYTES = 64L * 1024 * 1024;

    private final String replicaId;
    private final Socket socket;
    private final OutputStream output;

    /** Serializes all writes to {@link #output} (snapshot thread vs writer thread). */
    private final ReentrantLock outputLock = new ReentrantLock();

    /** Guards the queue and state flags below. A ReentrantLock (not synchronized) so virtual threads don't pin. */
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Condition stateChanged = stateLock.newCondition();
    private final Deque<byte[]> pendingCommands = new ArrayDeque<>();
    private long pendingBytes;
    private boolean snapshotting = true;
    private boolean writing;
    private volatile boolean closed;

    private volatile Consumer<ReplicaConnection> failureListener = c -> { };

    private volatile int listeningPort;
    private volatile long ackOffset = -1;
    private volatile long lastAckMillis = System.currentTimeMillis();

    /** Creates a connection writing to {@code socket} through a 64 KiB buffer. */
    public ReplicaConnection(String replicaId, Socket socket) throws IOException {
        this(replicaId,
                new BufferedOutputStream(socket.getOutputStream(), 64 * 1024),
                socket);
    }

    /** Creates a socket-less connection (used by tests). */
    public ReplicaConnection(String replicaId, OutputStream output) {
        this(replicaId, output, null);
    }

    private ReplicaConnection(String replicaId, OutputStream output, Socket socket) {
        this.replicaId = replicaId;
        this.socket = socket;
        this.output = output;
    }

    public String getReplicaId() {
        return replicaId;
    }

    /** @return {@code "host"} of the replica, or "unknown" for socket-less test connections */
    public String getRemoteHost() {
        if (socket != null && socket.getRemoteSocketAddress() instanceof java.net.InetSocketAddress address
                && address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return "unknown";
    }

    /** @return the port the replica announced with {@code REPLCONF listening-port} (0 if unknown) */
    public int getListeningPort() {
        return listeningPort;
    }

    public void setListeningPort(int port) {
        this.listeningPort = port;
    }

    /** @return the last stream offset the replica acknowledged, or -1 if it never did */
    public long getAckOffset() {
        return ackOffset;
    }

    /** @return seconds since the replica last acknowledged (replication lag indicator) */
    public long getLagSeconds() {
        return (System.currentTimeMillis() - lastAckMillis) / 1000;
    }

    /** Records an acknowledgement ({@code REPLCONF ACK <offset>}) from the replica. */
    public void recordAck(long offset) {
        this.ackOffset = Math.max(this.ackOffset, offset);
        this.lastAckMillis = System.currentTimeMillis();
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /** @return bytes currently queued and not yet written */
    public long getPendingBytes() {
        stateLock.lock();
        try {
            return pendingBytes;
        } finally {
            stateLock.unlock();
        }
    }

    /** Registers the callback invoked once when the link fails or is dropped. */
    public void setFailureListener(Consumer<ReplicaConnection> listener) {
        this.failureListener = listener == null ? c -> { } : listener;
    }

    /**
     * Queues {@code command} for the replica. Non-blocking: no network I/O.
     *
     * @throws IOException if the connection is closed or the replica is more
     *                     than 64 MiB behind (caller must drop the replica)
     */
    public void send(byte[] command) throws IOException {
        stateLock.lock();
        try {
            if (closed) {
                throw new IOException("Replica connection is closed");
            }
            if (pendingBytes > MAX_PENDING_BYTES - command.length) {
                throw new IOException(
                        "Replica fell behind; pending replication exceeded 64 MiB"
                );
            }
            pendingCommands.addLast(command);
            pendingBytes += command.length;
            stateChanged.signalAll();
        } finally {
            stateLock.unlock();
        }
    }

    /** Writes one snapshot command directly (snapshot phase only). */
    public void writeSnapshotCommand(byte[] command) throws IOException {
        outputLock.lock();
        try {
            ensureOpen();
            output.write(command);
        } finally {
            outputLock.unlock();
        }
    }

    /** Pushes buffered snapshot bytes to the socket (called periodically while streaming). */
    public void flushSnapshot() throws IOException {
        outputLock.lock();
        try {
            ensureOpen();
            output.flush();
        } finally {
            outputLock.unlock();
        }
    }

    /**
     * Ends the snapshot phase: flushes the snapshot and starts the writer
     * thread that sends everything queued so far and all future writes.
     */
    public void finishSnapshot() throws IOException {
        outputLock.lock();
        try {
            ensureOpen();
            output.flush();
        } finally {
            outputLock.unlock();
        }

        stateLock.lock();
        try {
            ensureOpen();
            snapshotting = false;
            stateChanged.signalAll();
        } finally {
            stateLock.unlock();
        }
        Thread.ofVirtual().name("RedCake-ReplicaWriter-" + replicaId).start(this::writerLoop);
    }

    /**
     * Waits until everything queued has been written (and the snapshot phase
     * is over). Mainly for tests and graceful shutdown.
     *
     * @return {@code true} if the queue drained within the timeout
     */
    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        stateLock.lock();
        try {
            while (snapshotting || !pendingCommands.isEmpty() || writing) {
                if (closed || remaining <= 0) {
                    return false;
                }
                remaining = stateChanged.awaitNanos(remaining);
            }
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    /** Closes the socket, discards the queue and wakes the writer so it exits. */
    public void close() {
        closed = true;
        stateLock.lock();
        try {
            pendingCommands.clear();
            pendingBytes = 0;
            stateChanged.signalAll();
        } finally {
            stateLock.unlock();
        }

        if (socket != null) {
            try {
                socket.close();
            } catch (Exception e) {
                // already closed
            }
        }
    }

    // ------------------------------------------------------------ internals

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Replica connection is closed");
        }
    }

    /** Drains the queue in batches until the connection closes or a write fails. */
    private void writerLoop() {
        while (true) {
            List<byte[]> batch;
            stateLock.lock();
            try {
                while (!closed && pendingCommands.isEmpty()) {
                    stateChanged.awaitUninterruptibly();
                }
                if (closed) {
                    return;
                }
                batch = new ArrayList<>(pendingCommands);
                pendingCommands.clear();
                pendingBytes = 0;
                writing = true;
            } finally {
                stateLock.unlock();
            }

            try {
                outputLock.lock();
                try {
                    for (byte[] command : batch) {
                        output.write(command);
                    }
                    output.flush();
                } finally {
                    outputLock.unlock();
                }
            } catch (IOException e) {
                markWriteFinished();
                log.warn("Replica {} write failed: {}", replicaId, e.getMessage());
                close();
                failureListener.accept(this);
                return;
            }
            markWriteFinished();
        }
    }

    private void markWriteFinished() {
        stateLock.lock();
        try {
            writing = false;
            stateChanged.signalAll();
        } finally {
            stateLock.unlock();
        }
    }
}
