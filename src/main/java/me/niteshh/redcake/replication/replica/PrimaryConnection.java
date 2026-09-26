package me.niteshh.redcake.replication.replica;

import org.springframework.stereotype.Component;
import java.net.Socket;
import java.net.InetSocketAddress;

@Component
public class PrimaryConnection {

    private Socket socket;

    private String host;

    private int port;

    public synchronized void connect(String host, int port) {
        if(isConnected()) {
            return;
        }

        try {
            this.host = host;
            this.port = port;
            Socket connected = new Socket();
            connected.setKeepAlive(true);
            connected.setTcpNoDelay(true);
            connected.connect(
                    new InetSocketAddress(host, port),
                    3_000
            );
            this.socket = connected;
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to primary at " + host + ":" + port, e);
        }
    }

    public synchronized boolean isConnected() {

        return socket != null
                && socket.isConnected()
                && !socket.isClosed();
    }

    public synchronized Socket getSocket() {
        return socket;
    }

    public synchronized String getHost() {
        return host;
    }

    public synchronized int getPort() {
        return port;
    }

    public synchronized void close() {
        if(socket == null) {
            return;
        }

        try {
            socket.close();
        } catch (Exception e) {
            // Connection already closed
        }
        socket = null;
        System.out.println("Disconnected from primary at " + host + ":" + port);
    }

}
