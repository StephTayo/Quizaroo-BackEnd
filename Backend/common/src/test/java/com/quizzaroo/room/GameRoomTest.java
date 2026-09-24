package com.quizzaroo.room;

import com.quizzaroo.protocol.ClientMessage;
import com.quizzaroo.protocol.RoomPhase;
import com.quizzaroo.protocol.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives whole games through the room with no network and no real time.
 *
 * The clock is a mutable fake and ids are sequential, so every assertion is
 * exact rather than approximate. This is the cheap place to find timing and
 * reconnect bugs -- doing it here instead of against a live socket is the
 * whole reason GameRoom has no transport in it.
 */
class GameRoomTest {

    /** A clock the test moves by hand. */
    static final class FakeClock extends Clock {
        private long millis = 1_700_000_000_000L;

        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }

        void advance(long by) { this.millis += by; }
    }

    private FakeClock clock;
    private GameRoom room;
    private String host;
    private String alice;
    private String bob;

    private static Quiz twoQuestionQuiz() {
        return new Quiz("Capitals", List.of(
                new Quiz.Item("Capital of Canada?", null,
                        List.of("Toronto", "Ottawa", "Vancouver", "Calgary"), 1, 20_000L, 1_000),
                new Quiz.Item("Capital of Japan?", null,
                        List.of("Osaka", "Kyoto", "Tokyo", "Nagoya"), 2, 20_000L, 1_000)));
    }

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        AtomicInteger counter = new AtomicInteger();
        room = new GameRoom("482931", twoQuestionQuiz(), clock,
                ScoringStrategy.SPEED_WEIGHTED, () -> "id-" + counter.incrementAndGet());

        host = joinAs("Host", true);
        alice = joinAs("Alice", false);
        bob = joinAs("Bob", false);
    }

    // ---------- helpers ----------

    private String joinAs(String nickname, boolean asHost) {
        List<Outbound> out = room.handle(
                new RoomCommand.Join(nickname, "roo-1", null, asHost));
        ServerMessage.Joined joined = (ServerMessage.Joined) out.get(0).message();
        return joined.playerId();
    }

    private List<Outbound> start() {
        return room.handle(new RoomCommand.Host(
                host, ClientMessage.HostAction.Action.START, null));
    }

    private List<Outbound> next() {
        return room.handle(new RoomCommand.Host(
                host, ClientMessage.HostAction.Action.NEXT, null));
    }

    private List<Outbound> answer(String playerId, int choice, long afterMs, long latencyMs) {
        clock.advance(afterMs);
        return room.handle(new RoomCommand.Answer(
                playerId, room.state().currentIndex(), choice, latencyMs));
    }

    private static <T extends ServerMessage> T firstOf(List<Outbound> out, Class<T> type) {
        return out.stream()
                .map(Outbound::message)
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type.getSimpleName() + " in output"));
    }

    private static <T extends ServerMessage> T messageTo(List<Outbound> out, String playerId, Class<T> type) {
        return out.stream()
                .filter(o -> o.recipient() instanceof Outbound.Recipient.Player p
                        && p.playerId().equals(playerId))
                .map(Outbound::message)
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No " + type.getSimpleName() + " addressed to " + playerId));
    }

    // ---------- lobby ----------

    @Nested
    @DisplayName("Lobby")
    class Lobby {

        @Test
        @DisplayName("joining tells the joiner who they are and tells everyone else they arrived")
        void joinBroadcasts() {
            List<Outbound> out = room.handle(
                    new RoomCommand.Join("Carol", "roo-2", null, false));

            ServerMessage.Joined joined = firstOf(out, ServerMessage.Joined.class);
            assertThat(joined.roomState()).isEqualTo(RoomPhase.LOBBY);
            assertThat(joined.reconnectToken()).isNotBlank();
            assertThat(joined.spectator()).isNull();

            ServerMessage.PlayerJoined announced = firstOf(out, ServerMessage.PlayerJoined.class);
            assertThat(announced.nickname()).isEqualTo("Carol");
            assertThat(announced.playerCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("the host is not counted as a player")
        void hostIsNotAContender() {
            assertThat(room.state().playerCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("duplicate nicknames are rejected")
        void duplicateNickname() {
            List<Outbound> out = room.handle(
                    new RoomCommand.Join("alice", "roo-2", null, false));
            ServerMessage.Error error = firstOf(out, ServerMessage.Error.class);
            assertThat(error.code()).isEqualTo(ServerMessage.Error.NICKNAME_TAKEN);
        }

        @Test
        @DisplayName("only the host can start the game")
        void playersCannotStart() {
            List<Outbound> out = room.handle(new RoomCommand.Host(
                    alice, ClientMessage.HostAction.Action.START, null));
            assertThat(firstOf(out, ServerMessage.Error.class).code())
                    .isEqualTo(ServerMessage.Error.NOT_HOST);
            assertThat(room.state().phase()).isEqualTo(RoomPhase.LOBBY);
        }
    }

    // ---------- the invariant ----------

    @Nested
    @DisplayName("Answer secrecy")
    class AnswerSecrecy {

        @Test
        @DisplayName("the question going out carries no correct answer")
        void questionHidesTheAnswer() {
            ServerMessage.Question question = firstOf(start(), ServerMessage.Question.class);

            assertThat(question.choices()).hasSize(4);
            assertThat(question.questionIndex()).isZero();
            assertThat(question.startedAt()).isEqualTo(clock.millis());
            // Compile-time guarantee: Question has no correctIndex component.
            assertThat(ServerMessage.Question.class.getRecordComponents())
                    .noneMatch(c -> c.getName().toLowerCase().contains("correct"));
        }

        @Test
        @DisplayName("the ack confirms receipt without saying whether it was right")
        void ackWithholdsCorrectness() {
            start();
            ServerMessage.AnswerAck ack = firstOf(
                    answer(alice, 1, 3_000, 40), ServerMessage.AnswerAck.class);

            assertThat(ack.accepted()).isTrue();
            assertThat(ack.reason()).isEqualTo(ServerMessage.AnswerAck.Reason.OK);
            assertThat(ServerMessage.AnswerAck.class.getRecordComponents())
                    .noneMatch(c -> c.getName().toLowerCase().contains("correct"));
        }

        @Test
        @DisplayName("the reveal is the first message carrying the correct answer")
        void revealCarriesTheAnswer() {
            start();
            answer(alice, 1, 3_000, 40);
            List<Outbound> out = answer(bob, 0, 1_000, 40);

            ServerMessage.Reveal reveal = messageTo(out, alice, ServerMessage.Reveal.class);
            assertThat(reveal.correctIndex()).isEqualTo(1);
            assertThat(reveal.yourCorrect()).isTrue();
            assertThat(reveal.distribution()).containsExactly(1, 1, 0, 0);
        }
    }

    // ---------- scoring ----------

    @Nested
    @DisplayName("Scoring")
    class Scoring {

        @Test
        @DisplayName("a faster correct answer beats a slower one")
        void fasterScoresHigher() {
            start();
            answer(alice, 1, 2_000, 0);
            answer(bob, 1, 10_000, 0);

            assertThat(room.state().player(alice).score())
                    .isGreaterThan(room.state().player(bob).score());
        }

        @Test
        @DisplayName("a correct answer never scores below half the base")
        void slowButCorrectStillScores() {
            start();
            answer(alice, 1, 19_999, 0);
            assertThat(room.state().player(alice).score()).isGreaterThanOrEqualTo(500);
        }

        @Test
        @DisplayName("a wrong answer scores nothing and breaks the streak")
        void wrongAnswerResetsStreak() {
            start();
            answer(alice, 0, 2_000, 0);
            assertThat(room.state().player(alice).score()).isZero();
            assertThat(room.state().player(alice).streak()).isZero();
        }

        @Test
        @DisplayName("latency is subtracted so a slow connection is not punished")
        void latencyIsCorrected() {
            start();
            answer(alice, 1, 5_000, 0);
            int fastConnection = room.state().player(alice).score();

            setUp();
            start();
            answer(alice, 1, 5_000, 600);
            int slowConnection = room.state().player(alice).score();

            assertThat(slowConnection).isGreaterThan(fastConnection);
        }

        @Test
        @DisplayName("consecutive correct answers build a streak bonus")
        void streakBonusAccrues() {
            start();
            answer(alice, 1, 2_000, 0);
            answer(bob, 0, 100, 0);
            next();
            next();
            answer(alice, 2, 2_000, 0);

            assertThat(room.state().player(alice).streak()).isEqualTo(2);
        }
    }

    // ---------- edge cases from the protocol spec ----------

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("a second answer is ignored, the first one stands")
        void firstAnswerWins() {
            start();
            answer(alice, 1, 2_000, 0);
            int afterFirst = room.state().player(alice).score();

            ServerMessage.AnswerAck ack = firstOf(
                    answer(alice, 0, 1_000, 0), ServerMessage.AnswerAck.class);

            assertThat(ack.accepted()).isFalse();
            assertThat(ack.reason()).isEqualTo(ServerMessage.AnswerAck.Reason.ALREADY_ANSWERED);
            assertThat(room.state().player(alice).score()).isEqualTo(afterFirst);
        }

        @Test
        @DisplayName("an answer after the time limit is rejected")
        void lateAnswerRejected() {
            start();
            ServerMessage.AnswerAck ack = firstOf(
                    answer(alice, 1, 20_001, 0), ServerMessage.AnswerAck.class);

            assertThat(ack.accepted()).isFalse();
            assertThat(ack.reason()).isEqualTo(ServerMessage.AnswerAck.Reason.TOO_LATE);
            assertThat(room.state().player(alice).score()).isZero();
        }

        @Test
        @DisplayName("the timer closes the question when not everyone has answered")
        void timerClosesQuestion() {
            start();
            answer(alice, 1, 3_000, 0);

            clock.advance(17_000);
            List<Outbound> out = room.handle(new RoomCommand.TimerExpired(0));

            assertThat(room.state().phase()).isEqualTo(RoomPhase.REVEAL);
            assertThat(messageTo(out, bob, ServerMessage.Reveal.class).yourCorrect()).isFalse();
        }

        @Test
        @DisplayName("a stale timer does not close the following question")
        void staleTimerIgnored() {
            start();
            answer(alice, 1, 1_000, 0);
            answer(bob, 1, 500, 0);
            next();
            next();

            assertThat(room.state().phase()).isEqualTo(RoomPhase.QUESTION_ACTIVE);
            room.handle(new RoomCommand.TimerExpired(0));
            assertThat(room.state().phase()).isEqualTo(RoomPhase.QUESTION_ACTIVE);
        }

        @Test
        @DisplayName("a dropped socket keeps the player and their score")
        void disconnectPreservesScore() {
            start();
            answer(alice, 1, 2_000, 0);
            int scored = room.state().player(alice).score();

            room.handle(new RoomCommand.Leave(alice, false));

            assertThat(room.state().player(alice)).isNotNull();
            assertThat(room.state().player(alice).connected()).isFalse();
            assertThat(room.state().player(alice).score()).isEqualTo(scored);
        }

        @Test
        @DisplayName("reconnecting with a valid token restores the player")
        void reconnectRestoresPlayer() {
            List<Outbound> joined = room.handle(
                    new RoomCommand.Join("Carol", "roo-2", null, false));
            String token = firstOf(joined, ServerMessage.Joined.class).reconnectToken();
            String carol = firstOf(joined, ServerMessage.Joined.class).playerId();

            start();
            answer(carol, 1, 2_000, 0);
            room.handle(new RoomCommand.Leave(carol, false));

            ServerMessage.Joined back = firstOf(
                    room.handle(new RoomCommand.Join("Carol", "roo-2", token, false)),
                    ServerMessage.Joined.class);

            assertThat(back.playerId()).isEqualTo(carol);
            assertThat(back.roomState()).isEqualTo(RoomPhase.QUESTION_ACTIVE);
            assertThat(room.state().player(carol).score()).isGreaterThan(0);
            assertThat(room.state().player(carol).connected()).isTrue();
        }

        @Test
        @DisplayName("an unknown reconnect token is refused")
        void badTokenRefused() {
            ServerMessage.Error error = firstOf(
                    room.handle(new RoomCommand.Join("Ghost", null, "not-a-token", false)),
                    ServerMessage.Error.class);
            assertThat(error.code()).isEqualTo(ServerMessage.Error.INVALID_TOKEN);
        }

        @Test
        @DisplayName("joining mid-question spectates until the next question")
        void midQuestionJoinerSpectates() {
            start();
            List<Outbound> out = room.handle(
                    new RoomCommand.Join("Late", "roo-3", null, false));
            String late = firstOf(out, ServerMessage.Joined.class).playerId();

            assertThat(firstOf(out, ServerMessage.Joined.class).spectator()).isTrue();

            ServerMessage.AnswerAck ack = firstOf(
                    answer(late, 1, 1_000, 0), ServerMessage.AnswerAck.class);
            assertThat(ack.reason()).isEqualTo(ServerMessage.AnswerAck.Reason.NOT_ACTIVE);

            answer(alice, 1, 1_000, 0);
            answer(bob, 1, 500, 0);
            next();
            next();
            assertThat(room.state().player(late).spectator()).isFalse();
        }

        @Test
        @DisplayName("a kicked player is removed and announced")
        void kickRemovesPlayer() {
            List<Outbound> out = room.handle(new RoomCommand.Host(
                    host, ClientMessage.HostAction.Action.KICK, bob));

            assertThat(firstOf(out, ServerMessage.PlayerLeft.class).playerId()).isEqualTo(bob);
            assertThat(room.state().player(bob)).isNull();
            assertThat(room.state().playerCount()).isEqualTo(1);
        }
    }

    // ---------- whole game ----------

    @Nested
    @DisplayName("Full game")
    class FullGame {

        @Test
        @DisplayName("a two-question game runs from lobby to ended")
        void playsThrough() {
            assertThat(room.state().phase()).isEqualTo(RoomPhase.LOBBY);

            start();
            assertThat(room.state().phase()).isEqualTo(RoomPhase.QUESTION_ACTIVE);

            answer(alice, 1, 2_000, 30);
            answer(bob, 0, 4_000, 80);
            assertThat(room.state().phase()).isEqualTo(RoomPhase.REVEAL);

            next();
            assertThat(room.state().phase()).isEqualTo(RoomPhase.SCOREBOARD);

            next();
            assertThat(room.state().phase()).isEqualTo(RoomPhase.QUESTION_ACTIVE);
            assertThat(room.state().currentIndex()).isEqualTo(1);

            answer(alice, 2, 3_000, 30);
            answer(bob, 2, 1_000, 80);
            next();
            next();

            assertThat(room.state().phase()).isEqualTo(RoomPhase.ENDED);
        }

        @Test
        @DisplayName("the scoreboard sends the top ten plus each player's own rank")
        void scoreboardShape() {
            start();
            answer(alice, 1, 2_000, 0);
            answer(bob, 0, 3_000, 0);

            List<Outbound> out = next();
            ServerMessage.Scoreboard board = messageTo(out, bob, ServerMessage.Scoreboard.class);

            assertThat(board.top()).hasSize(2);
            assertThat(board.top().get(0).nickname()).isEqualTo("Alice");
            assertThat(board.yourRank()).isEqualTo(2);
            assertThat(board.yourScore()).isZero();
        }

        @Test
        @DisplayName("every answer lands in the event stream")
        void eventsAreRecorded() {
            start();
            answer(alice, 1, 2_000, 40);
            answer(bob, 0, 3_000, 40);

            List<AnswerEvent> events = room.state().events();
            assertThat(events).hasSize(2);
            assertThat(events.get(0).playerId()).isEqualTo(alice);
            assertThat(events.get(0).correct()).isTrue();
            assertThat(events.get(0).effectiveTimeMs()).isEqualTo(2_000 - 20);
            assertThat(events.get(1).correct()).isFalse();
        }
    }
}
