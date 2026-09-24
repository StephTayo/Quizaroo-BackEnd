package com.quizzaroo.room;

import com.quizzaroo.protocol.ServerMessage;

/**
 * A message plus who should receive it.
 *
 * GameRoom returns these rather than writing to sockets, which is what keeps
 * it testable without a network. The transport decides how to deliver them --
 * and, for a broadcast, serializes the payload once and shares the buffer
 * across every channel.
 */
public record Outbound(Recipient recipient, ServerMessage message) {

    public static Outbound to(String playerId, ServerMessage message) {
        return new Outbound(new Recipient.Player(playerId), message);
    }

    public static Outbound all(ServerMessage message) {
        return new Outbound(new Recipient.Everyone(), message);
    }

    public static Outbound allExcept(String playerId, ServerMessage message) {
        return new Outbound(new Recipient.EveryoneExcept(playerId), message);
    }

    public static Outbound host(ServerMessage message) {
        return new Outbound(new Recipient.Host(), message);
    }

    public sealed interface Recipient {
        record Player(String playerId) implements Recipient {}
        record Everyone() implements Recipient {}
        record EveryoneExcept(String playerId) implements Recipient {}
        record Host() implements Recipient {}
    }
}
