package com.quizzaroo.server;

/**
 * One client connection, seen from the game's side.
 *
 * Deliberately transport-free: the delivery layer talks to this, and Netty
 * sits behind an implementation of it. That keeps recipient resolution and
 * fan-out testable with fakes instead of sockets, and it is the reason this
 * package has almost no Netty in it.
 */
public interface Connection {

    /** Stable id for this socket, assigned at accept time. Not the player id. */
    String id();

    /** Writes an already-encoded frame. Must not block the caller. */
    void send(String frame);

    /** Closes the socket. Safe to call more than once. */
    void close();

    /**
     * Round-trip time in milliseconds, measured by the transport's own
     * ping/pong -- never taken from anything the client claims.
     *
     * The room subtracts half of this from elapsed answer time, so a bad
     * estimate here quietly distorts every score. Return 0 when unknown;
     * correcting by nothing beats correcting by a guess.
     */
    long latencyMs();

    boolean isOpen();
}
