package com.quizzaroo.protocol;

/**
 * Messages sent from a client (player or host) to the server.
 *
 * Sealed on purpose: when a new command is added here, every exhaustive
 * switch over ClientMessage stops compiling until it is handled. That is the
 * point -- an unhandled command must be a build failure, never a silent
 * no-op in a live room.
 *
 * Records map 1:1 to the "$defs" entries in protocol/messages.schema.json.
 * Field names are the wire names; do not rename one without changing the
 * schema, or ProtocolConformanceTest will fail.
 */
public sealed interface ClientMessage {

    /** The wire discriminator, carried as "t" in the envelope. */
    String type();

    /** First message on a new connection. reconnectToken is null on a fresh join. */
    record Join(
            String pin,
            String nickname,
            String avatarId,
            String reconnectToken
    ) implements ClientMessage {
        public static final String TYPE = "JOIN";
        public String type() { return TYPE; }
    }

    /**
     * Round-trip probe used to estimate this client's latency. The server
     * echoes clientSentAt back with its own clock so the client can compute
     * an offset, and the server keeps rtt/2 for latency-corrected scoring.
     */
    record ClockSync(long clientSentAt) implements ClientMessage {
        public static final String TYPE = "CLOCK_SYNC";
        public String type() { return TYPE; }
    }

    /**
     * A player's answer. The server decides whether it counts and how many
     * points it earns; clientSentAt is advisory only and is never trusted
     * for scoring.
     */
    record Answer(
            int questionIndex,
            int choiceIndex,
            long clientSentAt
    ) implements ClientMessage {
        public static final String TYPE = "ANSWER";
        public String type() { return TYPE; }
    }

    /** Host-only controls. Rejected with an Error if sent by a player. */
    record HostAction(
            Action action,
            String targetPlayerId
    ) implements ClientMessage {
        public static final String TYPE = "HOST_ACTION";
        public String type() { return TYPE; }

        public enum Action { START, NEXT, SKIP, LOCK, KICK }
    }
}
