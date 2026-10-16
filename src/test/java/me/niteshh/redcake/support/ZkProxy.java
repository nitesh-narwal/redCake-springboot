package me.niteshh.redcake.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A TCP forwarder in front of ZooKeeper that tests can cut and heal: {@link #cut()} drops every connection and
 * refuses new ones, which is exactly what a network partition between one RedCake node and the ZooKeeper
 * ensemble looks like - the node keeps running, ZooKeeper keeps the node's session alive until it times out.
 */
public final class ZkProxy implements AutoCloseable {

    private final ServerSocket listener;
    private final String targetHost;
    private final int targetPort;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean blocked;
    private volatile boolean blackhole;
    private volatile boolean closed;

    public ZkProxy(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("ZkProxy-accept").start(this::acceptLoop);
    }

    /** {@code host:port} to give to the node instead of the real ensemble address. */
    public String connectString() {
        return "127.0.0.1:" + listener.getLocalPort();
    }

    /** Drops all connections and refuses new ones until {@link #heal()}. */
    public void cut() {
        blocked = true;
        sockets.forEach(ZkProxy::closeQuietly);
        sockets.clear();
    }

    /**
     * Keeps every connection open but swallows all traffic: nobody gets a reset or an error, the other side
     * simply goes silent. This is what a frozen process or a dropped-packets partition looks like - the
     * hard case, because no connection event announces it.
     */
    public void blackhole() {
        blackhole = true;
    }

    public void heal() {
        blocked = false;
        blackhole = false;
    }

    private void acceptLoop() {
        while (!closed) {
            Socket client;
            try {
                client = listener.accept();
            } catch (IOException e) {
                return;
            }
            if (blocked) {
                closeQuietly(client);
                continue;
            }
            try {
                Socket upstream = new Socket(targetHost, targetPort);
                sockets.add(client);
                sockets.add(upstream);
                pump(client, upstream);
                pump(upstream, client);
            } catch (IOException e) {
                closeQuietly(client);
            }
        }
    }

    private void pump(Socket from, Socket to) {
        Thread.ofPlatform().daemon().name("ZkProxy-pump").start(() -> {
            byte[] buffer = new byte[8192];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (!blackhole) {
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // the other side or cut() closed it
            } finally {
                closeQuietly(from);
                closeQuietly(to);
                sockets.remove(from);
                sockets.remove(to);
            }
        });
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            listener.close();
        } catch (IOException ignored) {
            // already closed
        }
        cut();
    }
}
