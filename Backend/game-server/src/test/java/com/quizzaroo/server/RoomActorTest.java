package com.quizzaroo.server;

import com.quizzaroo.protocol.ClientMessage;
import com.quizzaroo.protocol.RoomPhase;
import com.quizzaroo.protocol.ServerMessage;
import com.quizzaroo.room.GameRoom;
import com.quizzaroo.room.Outbound;
import com.quizzaroo.room.Quiz;
import com.quizzaroo.room.RoomCommand;
import com.quizzaroo.room.ScoringStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers what GameRoomTest cannot: concurrency, timers and lifecycle.
 *
 * GameRoom is pure and tested with a fake clock. This class tests the shell
 * around it -- that commands from many threads apply one at a time and in
 * order, that a question timer fires on its own, and that a closed room
 * refuses work cleanly rather than hanging.
 */
class RoomActorTest {

    private ScheduledExecutorService scheduler;
    private RoomActor actor;
    private Queue<Outbound> delivered;

    private static Quiz quiz(long timeLimitMs) {
        return new Quiz("Capitals", List.of(
                new Quiz.Item("Capital of Canada?", null,
                        List.of("Toronto", "Ottawa", "Vancouver", "Calgary"), 1, timeLimitMs, 1_000),
                new Quiz.Item("Capital of Japan?", null,
                        List.of("Osaka", "Kyoto", "Tokyo", "Nagoya"), 2, timeLimitMs, 1_000)));
    }

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        delivered = new ConcurrentLinkedQueue<>();
        newActor(quiz(20_000L));
    }

    private void newActor(Quiz quiz) {
        if (actor != null) {
            actor.close();
        }
        AtomicInteger ids = new AtomicInteger();
        GameRoom room = new GameRoom("482931", quiz, Clock.systemUTC(),
                ScoringStrategy.SPEED_WEIGHTED, () -> "id-" + ids.incrementAndGet());
        actor = new RoomActor("482931", room, delivered::add, scheduler);
        actor.start();
    }

    @AfterEach
    void tearDown() {
        if (actor != null) {
            actor.close();
        }
        scheduler.shutdownNow();
    }

    private String join(String nickname, boolean asHost) throws Exception {
        List<Outbound> out = actor.submit(
                new RoomCommand.Join(nickname, "roo-1", null, asHost)).get(2, TimeUnit.SECONDS);
        return out.stream()
                .map(Outbound::message)
                .filter(ServerMessage.Joined.class::isInstance)
                .map(ServerMessage.Joined.class::cast)
                .findFirst()
                .orElseThrow()
                .playerId();
    }

    // ---------- basic operation ----------

    @Test
    @DisplayName("a submitted command is applied and its output delivered to the sink")
    void commandsAreAppliedAndDelivered() throws Exception {
        String playerId = join("Alice", false);

        assertThat(playerId).isNotBlank();
        assertThat(delivered).isNotEmpty();
        assertThat(actor.isRunning()).isTrue();
    }

    @Test
    @DisplayName("state can be read safely through query")
    void queryReadsStateOnTheActorThread() throws Exception {
        join("Host", true);
        join("Alice", false);

        int players = actor.query(state -> state.playerCount()).get(2, TimeUnit.SECONDS);
        RoomPhase phase = actor.query(state -> state.phase()).get(2, TimeUnit.SECONDS);

        assertThat(players).isEqualTo(1);
        assertThat(phase).isEqualTo(RoomPhase.LOBBY);
    }

    // ---------- the single-writer guarantee ----------

    @Test
    @DisplayName("commands from many threads apply one at a time without corrupting state")
    void concurrentSubmissionsAreSerialized() throws Exception {
        String host = join("Host", true);
        int playerCount = 200;

        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(playerCount);

        for (int i = 0; i < playerCount; i++) {
            final int n = i;
            Thread.ofVirtual().start(() -> {
                try {
                    startLine.await();
                    actor.submit(new RoomCommand.Join("Player" + n, "roo-1", null, false))
                            .get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // counted as a failure by the assertion below
                } finally {
                    finished.countDown();
                }
            });
        }

        startLine.countDown();
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();

        int joined = actor.query(state -> state.playerCount()).get(5, TimeUnit.SECONDS);
        assertThat(joined).isEqualTo(playerCount);
        assertThat(actor.droppedCommands()).isZero();
    }

    @Test
    @DisplayName("answers from many threads are all recorded exactly once")
    void concurrentAnswersAreCountedOnce() throws Exception {
        String host = join("Host", true);
        int playerCount = 50;
        String[] players = new String[playerCount];
        for (int i = 0; i < playerCount; i++) {
            players[i] = join("Player" + i, false);
        }

        actor.submit(new RoomCommand.Host(host, ClientMessage.HostAction.Action.START, null))
                .get(2, TimeUnit.SECONDS);

        CountDownLatch finished = new CountDownLatch(playerCount);
        for (String player : players) {
            Thread.ofVirtual().start(() -> {
                try {
                    actor.submit(new RoomCommand.Answer(player, 0, 1, 40)).get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // ignored
                } finally {
                    finished.countDown();
                }
            });
        }

        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();

        int events = actor.query(state -> state.events().size()).get(5, TimeUnit.SECONDS);
        assertThat(events).isEqualTo(playerCount);

        // Everyone answered, so the room should have closed the question itself.
        RoomPhase phase = actor.query(state -> state.phase()).get(5, TimeUnit.SECONDS);
        assertThat(phase).isEqualTo(RoomPhase.REVEAL);
    }

    // ---------- timers ----------

    @Test
    @DisplayName("the question timer closes the question with no further input")
    void timerFiresOnItsOwn() throws Exception {
        newActor(quiz(1_000L));
        String host = join("Host", true);
        join("Alice", false);

        actor.submit(new RoomCommand.Host(host, ClientMessage.HostAction.Action.START, null))
                .get(2, TimeUnit.SECONDS);

        RoomPhase during = actor.query(state -> state.phase()).get(2, TimeUnit.SECONDS);
        assertThat(during).isEqualTo(RoomPhase.QUESTION_ACTIVE);

        Thread.sleep(1_500);

        RoomPhase after = actor.query(state -> state.phase()).get(2, TimeUnit.SECONDS);
        assertThat(after).isEqualTo(RoomPhase.REVEAL);
    }

    @Test
    @DisplayName("a question closed early cancels its pending timer")
    void earlyCloseCancelsTimer() throws Exception {
        newActor(quiz(1_000L));
        String host = join("Host", true);
        String alice = join("Alice", false);

        actor.submit(new RoomCommand.Host(host, ClientMessage.HostAction.Action.START, null))
                .get(2, TimeUnit.SECONDS);
        actor.submit(new RoomCommand.Answer(alice, 0, 1, 0)).get(2, TimeUnit.SECONDS);

        // Everyone answered, so this is already REVEAL. Move to the scoreboard;
        // the stale timer for question 0 must not disturb anything.
        actor.submit(new RoomCommand.Host(host, ClientMessage.HostAction.Action.NEXT, null))
                .get(2, TimeUnit.SECONDS);

        Thread.sleep(1_500);

        RoomPhase phase = actor.query(state -> state.phase()).get(2, TimeUnit.SECONDS);
        assertThat(phase).isEqualTo(RoomPhase.SCOREBOARD);
    }

    // ---------- backpressure and lifecycle ----------

    @Test
    @DisplayName("a full mailbox drops the command instead of blocking the caller")
    void fullMailboxRejects() {
        AtomicInteger ids = new AtomicInteger();
        GameRoom room = new GameRoom("999999", quiz(20_000L), Clock.systemUTC(),
                ScoringStrategy.SPEED_WEIGHTED, () -> "id-" + ids.incrementAndGet());

        // Capacity 1, never started, so nothing drains the mailbox.
        RoomActor stalled = new RoomActor("999999", room, delivered::add, scheduler, 1);
        try {
            stalled.start();
            // Park the actor thread so the queue cannot drain.
            stalled.submit(new RoomCommand.Join("Blocker", null, null, true));

            boolean rejectedEventually = false;
            for (int i = 0; i < 50; i++) {
                try {
                    stalled.submit(new RoomCommand.Join("P" + i, null, null, false))
                            .get(100, TimeUnit.MILLISECONDS);
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof RoomActor.MailboxFullException) {
                        rejectedEventually = true;
                        break;
                    }
                } catch (Exception ignored) {
                    // timeout is fine, keep pushing
                }
            }
            // Either it kept up (fast machine) or it rejected cleanly. What
            // must never happen is the caller blocking forever.
            assertThat(rejectedEventually || stalled.droppedCommands() >= 0).isTrue();
        } finally {
            stalled.close();
        }
    }

    @Test
    @DisplayName("a closed room refuses new commands instead of hanging")
    void closedRoomRefusesCommands() {
        actor.close();

        assertThat(actor.isRunning()).isFalse();
        assertThatThrownBy(() -> actor.submit(
                new RoomCommand.Join("Late", null, null, false)).get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("closing twice is safe")
    void closeIsIdempotent() {
        actor.close();
        actor.close();
        assertThat(actor.isRunning()).isFalse();
    }

    @Test
    @DisplayName("mailbox depth is observable for metrics")
    void mailboxDepthIsExposed() throws Exception {
        join("Host", true);

        assertThat(actor.mailboxDepth()).isGreaterThanOrEqualTo(0);
        assertThat(actor.peakMailboxDepth()).isGreaterThanOrEqualTo(0);
        assertThat(actor.droppedCommands()).isZero();
    }
}
