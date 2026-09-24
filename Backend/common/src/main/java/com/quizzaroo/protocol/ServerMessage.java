package com.quizzaroo.protocol;

import java.util.List;

/**
 * Messages sent from the server to a client.
 *
 * Invariant: the correct answer never appears in any message before Reveal.
 * Question deliberately has no correctIndex field -- that is enforced here by
 * the type system, not by remembering.
 */
public sealed interface ServerMessage {

    /** The wire discriminator, carried as "t" in the envelope. */
    String type();

    /**
     * Acknowledges a successful join. reconnectToken is the player's identity
     * for the rest of the game -- it survives socket drops, unlike a channel
     * id. spectator is true when the player arrived mid-question and will
     * start playing from the next one.
     */
    record Joined(
            String playerId,
            String reconnectToken,
            RoomPhase roomState,
            int playerCount,
            String quizTitle,
            Boolean spectator
    ) implements ServerMessage {
        public static final String TYPE = "JOINED";
        public String type() { return TYPE; }
    }

    record ClockSyncAck(
            long clientSentAt,
            long serverTime
    ) implements ServerMessage {
        public static final String TYPE = "CLOCK_SYNC_ACK";
        public String type() { return TYPE; }
    }

    /**
     * A question going live. startedAt is server-stamped and is the only
     * timing reference that counts.
     */
    record Question(
            int questionIndex,
            int totalQuestions,
            String text,
            String imageUrl,
            List<Choice> choices,
            long timeLimitMs,
            long startedAt
    ) implements ServerMessage {
        public static final String TYPE = "QUESTION";
        public String type() { return TYPE; }

        public record Choice(int index, String text) {}
    }

    /**
     * Confirms receipt of an answer. accepted is false when the answer was a
     * duplicate, arrived after the timer, or the question was not active --
     * reason says which.
     */
    record AnswerAck(
            int questionIndex,
            boolean accepted,
            Reason reason
    ) implements ServerMessage {
        public static final String TYPE = "ANSWER_ACK";
        public String type() { return TYPE; }

        public enum Reason { OK, ALREADY_ANSWERED, TOO_LATE, NOT_ACTIVE }
    }

    /**
     * The only message that carries correctIndex. distribution is the count of
     * players who picked each choice, indexed by choice index.
     *
     * The your* fields are per-player, so this one is serialized per recipient;
     * everything else in a reveal burst is serialized once and shared.
     */
    record Reveal(
            int questionIndex,
            int correctIndex,
            List<Integer> distribution,
            Integer yourChoiceIndex,
            Boolean yourCorrect,
            Integer pointsAwarded,
            Integer streak,
            Integer totalScore
    ) implements ServerMessage {
        public static final String TYPE = "REVEAL";
        public String type() { return TYPE; }
    }

    /**
     * Top N plus the recipient's own rank -- never the full player list. At
     * 200 players a full board would be the single heaviest frame in the game
     * and almost none of it is read.
     */
    record Scoreboard(
            List<Entry> top,
            int yourRank,
            int yourScore,
            Integer rankDelta
    ) implements ServerMessage {
        public static final String TYPE = "SCOREBOARD";
        public String type() { return TYPE; }

        public record Entry(
                int rank,
                String playerId,
                String nickname,
                int score,
                String avatarId,
                Integer streak,
                Integer rankDelta
        ) {}
    }

    /** Someone joined the lobby. Broadcast to everyone already in the room. */
    record PlayerJoined(
            String playerId,
            String nickname,
            String avatarId,
            int playerCount
    ) implements ServerMessage {
        public static final String TYPE = "PLAYER_JOINED";
        public String type() { return TYPE; }
    }

    /** Someone left or was kicked. Same payload shape as PlayerJoined. */
    record PlayerLeft(
            String playerId,
            String nickname,
            String avatarId,
            int playerCount
    ) implements ServerMessage {
        public static final String TYPE = "PLAYER_LEFT";
        public String type() { return TYPE; }
    }

    record Error(
            int code,
            String message
    ) implements ServerMessage {
        public static final String TYPE = "ERROR";
        public String type() { return TYPE; }

        public static final int ROOM_NOT_FOUND     = 1001;
        public static final int ROOM_FULL          = 1002;
        public static final int NICKNAME_TAKEN     = 1003;
        public static final int GAME_ALREADY_ENDED = 1004;
        public static final int NOT_HOST           = 1005;
        public static final int INVALID_TOKEN      = 1006;
        public static final int RATE_LIMITED       = 1007;
    }
}
