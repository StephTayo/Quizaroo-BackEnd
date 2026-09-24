package com.quizzaroo.protocol;

/**
 * The room state machine. Exactly one phase is active at a time, and every
 * transition is driven by the server, never by a client.
 *
 * LOBBY -> QUESTION_INTRO -> QUESTION_ACTIVE -> LOCKED -> REVEAL -> SCOREBOARD
 *            ^                                                          |
 *            +----------------------------------------------------------+
 *                                                                       |
 *                                                          PODIUM -> ENDED
 */
public enum RoomPhase {
    LOBBY,
    QUESTION_INTRO,
    QUESTION_ACTIVE,
    LOCKED,
    REVEAL,
    SCOREBOARD,
    PODIUM,
    ENDED
}
