package me.niteshh.redcake.pubsub;

import me.niteshh.redcake.resp.RespValue;

import java.io.IOException;

/**
 * The receiving end of a subscription: anything that can be sent an
 * unsolicited message. A client connection implements it by writing the
 * message to its socket under its output lock, so a push can never interleave
 * with a normal command reply.
 */
public interface Subscriber {

    /** Writes and flushes {@code message} to the subscriber. */
    void push(RespValue message) throws IOException;
}
