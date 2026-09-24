package com.quizzaroo.room;

/**
 * How an answer converts into points.
 *
 * Behind an interface from day one because game modes want different curves,
 * and hosts will eventually want to switch speed scoring off entirely.
 */
@FunctionalInterface
public interface ScoringStrategy {

    int score(boolean correct, long effectiveTimeMs, long timeLimitMs, int basePoints, int streak);

    /**
     * The Kahoot-style curve: a correct answer never scores below half the
     * base, so a slow-but-right player still banks something.
     *
     *   points = base * (1 - (timeUsed / timeLimit) / 2) + streakBonus
     *
     * Streak bonus is 100 per consecutive correct answer, capped at 6.
     */
    ScoringStrategy SPEED_WEIGHTED = (correct, effectiveTimeMs, timeLimitMs, basePoints, streak) -> {
        if (!correct) {
            return 0;
        }
        long used = Math.max(0, Math.min(effectiveTimeMs, timeLimitMs));
        double speedFactor = 1.0 - ((double) used / timeLimitMs) / 2.0;
        int points = (int) Math.round(basePoints * speedFactor);
        return points + Math.min(streak, 6) * 100;
    };

    /** Flat scoring: correctness only, no speed component. */
    ScoringStrategy FLAT = (correct, effectiveTimeMs, timeLimitMs, basePoints, streak) ->
            correct ? basePoints : 0;
}
