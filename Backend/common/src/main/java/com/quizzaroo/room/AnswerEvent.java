package com.quizzaroo.room;

/**
 * An immutable record of one answer. The ordered stream of these is the
 * room's source of truth: leaderboards, streaks, accuracy stats and every
 * game mode are projections over it, never separately maintained state.
 *
 * effectiveTimeMs is latency-corrected; receivedAt is raw. Scoring uses the
 * former so a player on slow wifi is not punished for their connection.
 */
public record AnswerEvent(
        String playerId,
        int questionIndex,
        int choiceIndex,
        long receivedAt,
        long latencyMs,
        long effectiveTimeMs,
        boolean correct,
        int pointsAwarded,
        int streakAfter
) {}
