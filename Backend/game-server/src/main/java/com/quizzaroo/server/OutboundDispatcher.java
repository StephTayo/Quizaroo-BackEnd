package com.quizzaroo.server;

import com.quizzaroo.protocol.ServerMessage;
import com.quizzaroo.room.Outbound;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * Turns Outbound messages into writes on real connections.
 *
 * The hot path in this whole system is the reveal burst: a question closes
 * and every player in the room needs a frame at once, across every room on
 * the node simultaneously. The rule that matters is below -- a message going
 * to many people is encoded ONCE and the same string written to each
 * channel. Encoding per recipient at 200 players is the difference between
 * a 5ms reveal and a 300ms one.
 *
 * The encoder is injected rather than constructed here so this class stays
 * testable without a JSON library, and so the wire format can move to
 * MessagePack later without touching delivery.
 */
public final class OutboundDispatcher {

    private final ConnectionRegistry connections;
    private final BiFunction<Long, ServerMessage, String> encoder;
    private final AtomicLong seq = new AtomicLong();

    private final AtomicLong framesWritten = new AtomicLong();
    private final AtomicLong encodeCount = new AtomicLong();
    private final AtomicLong undeliverable = new AtomicLong();

    public OutboundDispatcher(ConnectionRegistry connections,
                              BiFunction<Long, ServerMessage, String> encoder) {
        this.connections = connections;
        this.encoder = encoder;
    }

    /** Delivers everything one command produced. */
    public void dispatch(List<Outbound> batch) {
        for (Outbound outbound : batch) {
            dispatch(outbound);
        }
    }

    public void dispatch(Outbound outbound) {
        switch (outbound.recipient()) {
            case Outbound.Recipient.Player p -> sendTo(p.playerId(), outbound.message());
            case Outbound.Recipient.Host h -> {
                String host = connections.hostPlayerId();
                if (host != null) {
                    sendTo(host, outbound.message());
                }
            }
            case Outbound.Recipient.Everyone e -> broadcast(outbound.message(), null);
            case Outbound.Recipient.EveryoneExcept e -> broadcast(outbound.message(), e.playerId());
        }
    }

    private void sendTo(String playerId, ServerMessage message) {
        Connection connection = connections.forPlayer(playerId);
        if (connection == null || !connection.isOpen()) {
            // Expected during a reconnect: the room is still producing
            // messages for a player whose socket has gone. Their state
            // survives and they catch up on rejoin.
            undeliverable.incrementAndGet();
            return;
        }
        connection.send(encodeOnce(message));
        framesWritten.incrementAndGet();
    }

    /**
     * One encode, many writes. Everything expensive happens before the loop.
     */
    private void broadcast(ServerMessage message, String excludePlayerId) {
        String excludedConnectionId = null;
        if (excludePlayerId != null) {
            Connection excluded = connections.forPlayer(excludePlayerId);
            excludedConnectionId = excluded == null ? null : excluded.id();
        }

        String frame = encodeOnce(message);

        for (Connection connection : connections.open()) {
            if (excludedConnectionId != null && excludedConnectionId.equals(connection.id())) {
                continue;
            }
            connection.send(frame);
            framesWritten.incrementAndGet();
        }
    }

    private String encodeOnce(ServerMessage message) {
        encodeCount.incrementAndGet();
        return encoder.apply(seq.incrementAndGet(), message);
    }

    // ---------- metrics ----------

    /**
     * Frames written divided by encodes performed. Should be well above 1
     * during a reveal; if it sits near 1, the fan-out optimisation has been
     * lost somewhere and the node will fall over sooner than the capacity
     * numbers predict.
     */
    public double fanOutRatio() {
        long encodes = encodeCount.get();
        return encodes == 0 ? 0.0 : (double) framesWritten.get() / encodes;
    }

    public long framesWritten() {
        return framesWritten.get();
    }

    public long encodeCount() {
        return encodeCount.get();
    }

    public long undeliverable() {
        return undeliverable.get();
    }
}
