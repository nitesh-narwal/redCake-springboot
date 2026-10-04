package me.niteshh.redcake.replication.primary;

import lombok.Getter;

import java.io.IOException;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;

@Getter
public class ReplicaConnection {

    private final String replicaId;

    private final Socket socket;
    private final OutputStream output;
    private final ReentrantLock outputLock = new ReentrantLock();
    private final Object stateLock = new Object();
    private final Queue<byte[]> pendingCommands = new ArrayDeque<>();
    private static final long MAX_PENDING_BYTES = 64L * 1024 * 1024;
    private long pendingBytes;

    private boolean snapshotting = true;
    private volatile boolean closed;

    /**
     * Creates a new ReplicaConnection with the specified replica ID and socket.
     * Means that this connection is established with a replica identified by `replicaId` and communicates over the provided `socket`.
     *
     * @param replicaId the unique identifier for the replica
     * @param socket    the socket connected to the replica
     * @throws IOException if an I/O error occurs when creating the output stream
     */
    public ReplicaConnection(
            String replicaId,
            Socket socket
    ) throws IOException {
        this(
                replicaId,
                new BufferedOutputStream(
                        socket.getOutputStream(),
                        64 * 1024
                ),
                socket
        );  // This constructor initializes the output stream with a buffer size of 64 KiB for efficient data transmission.
            // And this constructor is used when a socket connection is already established with the replica,
            // allowing for direct communication over that socket.
    }

    public ReplicaConnection(
            String replicaId,
            OutputStream output
    ) {
        this(replicaId, output, null);
    }

    private ReplicaConnection(
            String replicaId,
            OutputStream output,
            Socket socket
    ) {
        this.replicaId = replicaId;
        this.socket = socket;
        this.output = output;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void send(byte[] command) throws IOException { // This method is responsible for sending a command to the replica.
        synchronized (stateLock) {
            // Synchronization is used to ensure that the state of the connection
            // (e.g., whether it's closed or in snapshotting mode) is checked and modified safely across multiple threads.
            if (closed || snapshotting) { // If the connection is closed or if the replica is still in the snapshotting phase, the command is queued for later sending.
                if (pendingBytes > MAX_PENDING_BYTES - command.length) {
                    throw new IOException(
                            "Replica fell behind during snapshot; "
                                    + "pending replication exceeded 64 MiB"
                    );
                }

                pendingCommands.add(command);
                pendingBytes += command.length;
                return;
            }
        }

        write(command);
    }

    public void writeSnapshotCommand(byte[] command)
            throws IOException {
        if (closed) {
            throw new IOException("Replica connection is closed");
        }

        outputLock.lock();
        try {
            if (closed) {
                throw new IOException("Replica connection is closed");
            }

            output.write(command);
        } finally {
            outputLock.unlock();
        }
    }

    public void finishSnapshot() throws IOException {
        outputLock.lock();
        try {
            List<byte[]> pending;
            synchronized (stateLock) {
                if (closed) {
                    throw new IOException("Replica connection is closed");
                }

                pending = new ArrayList<>(pendingCommands);
                pendingCommands.clear();
                pendingBytes = 0;
                snapshotting = false;
            }

            for (byte[] command : pending) {
                output.write(command);
            }
            output.flush();
        } finally {
            outputLock.unlock();
        }
    }

    public void flushSnapshot() throws IOException {
        outputLock.lock();
        try {
            if (closed) {
                throw new IOException("Replica connection is closed");
            }
            output.flush();
        } finally {
            outputLock.unlock();
        }
    }

    private void write(byte[] command) throws IOException {
        outputLock.lock();
        try {
            if (closed || (socket != null && !isConnected())) {
                throw new IOException("Replica connection is closed");
            }

            output.write(command);
            output.flush();
        } finally {
            outputLock.unlock();
        }
    }

    public void close() {
        closed = true;
        synchronized (stateLock) {
            pendingCommands.clear();
            pendingBytes = 0;
        }

        if (socket != null) {
            try {
                socket.close();
            } catch (Exception e) {
                // Connection already closed
            }
        }
    }
}
