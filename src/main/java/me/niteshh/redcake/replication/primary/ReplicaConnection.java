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
        );
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

    public void send(byte[] command) throws IOException {
        synchronized (stateLock) {
            if (closed || snapshotting) {
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
