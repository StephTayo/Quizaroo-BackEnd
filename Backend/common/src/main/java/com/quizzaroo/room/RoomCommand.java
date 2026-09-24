package com.quizzaroo.room;

import com.quizzaroo.protocol.ClientMessage;

/**
 * Everything that can change a room's state.
 *
 * The transport layer never touches room state directly. It decodes a frame,
 * builds one of these, and hands it to the room's single writer. Timers do
 * the same through TimerExpired. That single-writer rule is what makes the
 * game logic free of locks and deterministic under test.
 *
 * Sealed, so a new command that GameRoom does not handle is a compile error.
 */
public sealed interface RoomCommand {

    /**
     * A connection asking to join. reconnectToken is null for a fresh join;
     * when present and valid, the player is rehydrated instead of created.
     */
    record Join(
            String nickname,
            String avatarId,
            String reconnectToken,
            boolean asHost
    ) implements RoomCommand {}

    /**
     * A player's answer.
     *
     * latencyMs is measured by the transport (from its ping/pong round trip),
     * not taken from the client's own claim about when it sent the frame.
     * The room subtracts half of it to get the effective answer time.
     */
    record Answer(
            String playerId,
            int questionIndex,
            int choiceIndex,
            long latencyMs
    ) implements RoomCommand {}

    /** Clock probe. Answered immediately with the server's time. */
    record ClockSync(String playerId, long clientSentAt) implements RoomCommand {}

    /** A player disconnected or left deliberately. */
    record Leave(String playerId, boolean permanent) implements RoomCommand {}

    /** Host-only control. Rejected with an Error if playerId is not the host. */
    record Host(
            String playerId,
            ClientMessage.HostAction.Action action,
            String targetPlayerId
    ) implements RoomCommand {}

    /**
     * Raised by the room's own timer when a question's time limit elapses.
     * Carries the question index so a late timer for a question that already
     * closed can be ignored rather than closing the next one.
     */
    record TimerExpired(int questionIndex) implements RoomCommand {}
}
