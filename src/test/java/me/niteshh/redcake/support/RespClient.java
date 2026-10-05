package me.niteshh.redcake.support;

import me.niteshh.redcake.replication.RespCommandCodec;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal blocking RESP client for tests. {@link #call} returns replies in a
 * readable form: {@code +OK}, {@code -ERR ...}, {@code :5}, {@code "bulk"},
 * {@code (nil)} or {@code [a, b]} for arrays.
 */
public final class RespClient implements Closeable {

    private final Socket socket;
    private final InputStream in;

    public RespClient(String host, int port) throws IOException {
        socket = new Socket(host, port);
        socket.setSoTimeout(5_000);
        in = new BufferedInputStream(socket.getInputStream());
    }

    public RespClient(Socket socket) throws IOException {
        this.socket = socket;
        socket.setSoTimeout(5_000);
        this.in = new BufferedInputStream(socket.getInputStream());
    }

    public Socket socket() {
        return socket;
    }

    /** Sends one command and reads one reply. */
    public String call(String... command) throws IOException {
        send(command);
        return read();
    }

    /** Alias of {@link #call}; reads naturally when the expected reply is a null array. */
    public String read0(String... command) throws IOException {
        return call(command);
    }

    public void send(String... command) throws IOException {
        socket.getOutputStream().write(RespCommandCodec.encode(List.of(command)));
        socket.getOutputStream().flush();
    }

    public void sendRaw(String raw) throws IOException {
        socket.getOutputStream().write(raw.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }

    /** Sets how long {@link #read()} waits before failing with a timeout. */
    public void setTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    /** Reads one complete reply. Returns {@code null} at end of stream. */
    public String read() throws IOException {
        int type = in.read();
        if (type == -1) {
            return null;
        }
        String line = readLine();
        return switch (type) {
            case '+' -> "+" + line;
            case '-' -> "-" + line;
            case ':' -> ":" + line;
            case '$' -> {
                int length = Integer.parseInt(line);
                if (length < 0) {
                    yield "(nil)";
                }
                byte[] data = in.readNBytes(length + 2);
                yield "\"" + new String(data, 0, length, StandardCharsets.ISO_8859_1) + "\"";
            }
            case '*' -> {
                int count = Integer.parseInt(line);
                if (count < 0) {
                    yield "(nil)";
                }
                List<String> items = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    items.add(read());
                }
                yield items.toString();
            }
            default -> throw new IOException("Unexpected reply type: " + (char) type);
        };
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                in.read(); // '\n'
                return line.toString();
            }
            line.append((char) c);
        }
        throw new IOException("Unexpected end of stream");
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
