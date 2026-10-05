package me.niteshh.redcake.resp;

import java.io.IOException;

/**
 * Signals that bytes received from a peer are not valid RESP.
 *
 * <p>It extends {@link IOException} so existing {@code catch (IOException)}
 * code keeps working, but lets {@code ClientHandler} distinguish "client sent
 * garbage" (reply {@code -ERR Protocol error: ...} then close) from "socket
 * failed" (just close).
 */
public class ProtocolException extends IOException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
