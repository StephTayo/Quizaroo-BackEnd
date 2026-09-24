package com.quizzaroo.room;

import com.quizzaroo.protocol.ClientMessage;
import com.quizzaroo.protocol.RoomPhase;
import com.quizzaroo.protocol.ServerMessage;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The authoritative state machine for one room.
 *
 * Pure logic: no sockets, no threads, no Instant.now(). Every input is a
 * RoomCommand and every output is a list of Outbound messages, so a full
 * twelve-question game runs in under a millisecond under test.
 *
 * Three rules this class exists to enforce:
 *   1. The correct answer never leaves the room before REVEAL.
 *   2. Timing is server-stamped; a client's claimed send time is never
 *      trusted for scoring.
 *   3. Identity is a reconnect token, not a connection -- a dropped socket
 *      loses nothing.
 */
public final class GameRoom {

    private final RoomState state;
    private final Clock clock;
    private final ScoringStrategy scoring;
    private final Supplier<String> idSupplier;

    public GameRoom(String pin, Quiz quiz, Clock clock) {
        this(pin, quiz, clock, ScoringStrategy.SPEED_WEIGHTED, () -> UUID.randomUUID().toString());
    }

    public GameRoom(String pin, Quiz quiz, Clock clock,
                    ScoringStrategy scoring, Supplier<String> idSupplier) {
        this.state = new RoomState(pin, quiz);
        this.clock = clock;
        this.scoring = scoring;
        this.idSupplier = idSupplier;
    }

    public RoomState state() {
        return state;
    }

    private long now() {
        return clock.millis();
    }

    /** Applies one command and returns everything that should go out. */
    public List<Outbound> handle(RoomCommand command) {
        return switch (command) {
            case RoomCommand.Join c         -> onJoin(c);
            case RoomCommand.Answer c       -> onAnswer(c);
            case RoomCommand.ClockSync c    -> onClockSync(c);
            case RoomCommand.Leave c        -> onLeave(c);
            case RoomCommand.Host c         -> onHost(c);
            case RoomCommand.TimerExpired c -> onTimerExpired(c);
        };
    }

    // ---------- join and leave ----------

    private List<Outbound> onJoin(RoomCommand.Join c) {
        if (state.phase() == RoomPhase.ENDED) {
            return List.of(Outbound.to("pending", new ServerMessage.Error(
                    ServerMessage.Error.GAME_ALREADY_ENDED, "This game has ended")));
        }

        // Reconnect: the token, not the socket, is the identity.
        if (c.reconnectToken() != null) {
            RoomState.Player existing = state.playerByToken(c.reconnectToken());
            if (existing == null) {
                return List.of(Outbound.to("pending", new ServerMessage.Error(
                        ServerMessage.Error.INVALID_TOKEN, "Unrecognised reconnect token")));
            }
            existing.connected(true);
            return List.of(Outbound.to(existing.id(), new ServerMessage.Joined(
                    existing.id(),
                    existing.token(),
                    state.phase(),
                    state.playerCount(),
                    state.quiz().title(),
                    existing.spectator() ? Boolean.TRUE : null)));
        }

        if (state.nicknameTaken(c.nickname())) {
            return List.of(Outbound.to("pending", new ServerMessage.Error(
                    ServerMessage.Error.NICKNAME_TAKEN, "That nickname is already taken")));
        }

        // Joining mid-question means spectating until the next one starts.
        boolean spectator = !c.asHost() && state.phase() != RoomPhase.LOBBY;

        RoomState.Player player = new RoomState.Player(
                idSupplier.get(), idSupplier.get(), c.nickname(), c.avatarId(), spectator);
        state.addPlayer(player);
        if (c.asHost() && state.hostPlayerId() == null) {
            state.hostPlayerId(player.id());
        }

        List<Outbound> out = new ArrayList<>();
        out.add(Outbound.to(player.id(), new ServerMessage.Joined(
                player.id(),
                player.token(),
                state.phase(),
                state.playerCount(),
                state.quiz().title(),
                spectator ? Boolean.TRUE : null)));

        if (!state.isHost(player.id())) {
            out.add(Outbound.allExcept(player.id(), new ServerMessage.PlayerJoined(
                    player.id(), player.nickname(), player.avatarId(), state.playerCount())));
        }
        return out;
    }

    private List<Outbound> onLeave(RoomCommand.Leave c) {
        RoomState.Player player = state.player(c.playerId());
        if (player == null) {
            return List.of();
        }

        if (!c.permanent()) {
            // A dropped socket is not a departure. Keep the player and their
            // score so a reconnect inside the game picks up where they were.
            player.connected(false);
            return List.of();
        }

        state.removePlayer(c.playerId());
        return List.of(Outbound.all(new ServerMessage.PlayerLeft(
                player.id(), player.nickname(), player.avatarId(), state.playerCount())));
    }

    private List<Outbound> onClockSync(RoomCommand.ClockSync c) {
        return List.of(Outbound.to(c.playerId(),
                new ServerMessage.ClockSyncAck(c.clientSentAt(), now())));
    }

    // ---------- host controls ----------

    private List<Outbound> onHost(RoomCommand.Host c) {
        if (!state.isHost(c.playerId())) {
            return List.of(Outbound.to(c.playerId(), new ServerMessage.Error(
                    ServerMessage.Error.NOT_HOST, "Only the host can do that")));
        }

        return switch (c.action()) {
            case START -> state.phase() == RoomPhase.LOBBY ? startNextQuestion() : List.of();
            case NEXT  -> advance();
            case SKIP  -> state.phase() == RoomPhase.QUESTION_ACTIVE ? closeQuestion() : List.of();
            case LOCK  -> state.phase() == RoomPhase.QUESTION_ACTIVE ? closeQuestion() : List.of();
            case KICK  -> kick(c.targetPlayerId());
        };
    }

    private List<Outbound> kick(String targetPlayerId) {
        if (targetPlayerId == null) {
            return List.of();
        }
        return onLeave(new RoomCommand.Leave(targetPlayerId, true));
    }

    /** NEXT from a scoreboard moves on; from a reveal it shows the scoreboard. */
    private List<Outbound> advance() {
        return switch (state.phase()) {
            case REVEAL -> showScoreboard();
            case SCOREBOARD -> state.hasMoreQuestions() ? startNextQuestion() : finish();
            case LOBBY -> startNextQuestion();
            default -> List.of();
        };
    }

    // ---------- the question loop ----------

    private List<Outbound> startNextQuestion() {
        state.currentIndex(state.currentIndex() + 1);
        state.phase(RoomPhase.QUESTION_ACTIVE);
        state.questionStartedAt(now());

        // Anyone who joined mid-question is a full player from here on.
        state.players().forEach(p -> p.spectator(false));

        Quiz.Item item = state.currentItem();
        List<ServerMessage.Question.Choice> choices = new ArrayList<>();
        for (int i = 0; i < item.choices().size(); i++) {
            choices.add(new ServerMessage.Question.Choice(i, item.choices().get(i)));
        }

        // Note what does NOT go out here: correctIndex.
        return List.of(Outbound.all(new ServerMessage.Question(
                state.currentIndex(),
                state.quiz().size(),
                item.text(),
                item.imageUrl(),
                choices,
                item.timeLimitMs(),
                state.questionStartedAt())));
    }

    private List<Outbound> onAnswer(RoomCommand.Answer c) {
        RoomState.Player player = state.player(c.playerId());
        if (player == null) {
            return List.of();
        }

        if (state.phase() != RoomPhase.QUESTION_ACTIVE || c.questionIndex() != state.currentIndex()) {
            return List.of(Outbound.to(c.playerId(), new ServerMessage.AnswerAck(
                    c.questionIndex(), false, ServerMessage.AnswerAck.Reason.NOT_ACTIVE)));
        }
        if (player.spectator()) {
            return List.of(Outbound.to(c.playerId(), new ServerMessage.AnswerAck(
                    c.questionIndex(), false, ServerMessage.AnswerAck.Reason.NOT_ACTIVE)));
        }
        if (player.answeredIndex() == c.questionIndex()) {
            // First answer wins. A second is ignored, not scored.
            return List.of(Outbound.to(c.playerId(), new ServerMessage.AnswerAck(
                    c.questionIndex(), false, ServerMessage.AnswerAck.Reason.ALREADY_ANSWERED)));
        }

        Quiz.Item item = state.currentItem();
        long receivedAt = now();
        long raw = receivedAt - state.questionStartedAt();

        if (raw > item.timeLimitMs()) {
            return List.of(Outbound.to(c.playerId(), new ServerMessage.AnswerAck(
                    c.questionIndex(), false, ServerMessage.AnswerAck.Reason.TOO_LATE)));
        }

        // Latency correction: half the round trip is the inbound leg.
        long effective = Math.max(0, raw - c.latencyMs() / 2);
        boolean correct = c.choiceIndex() == item.correctIndex();
        int streakAfter = correct ? player.streak() + 1 : 0;
        int points = scoring.score(correct, effective, item.timeLimitMs(), item.basePoints(), player.streak());

        player.answeredIndex(c.questionIndex());
        player.streak(streakAfter);
        player.addScore(points);

        state.record(new AnswerEvent(
                player.id(), c.questionIndex(), c.choiceIndex(),
                receivedAt, c.latencyMs(), effective, correct, points, streakAfter));

        List<Outbound> out = new ArrayList<>();
        // Receipt only. Correctness is withheld until the reveal so the
        // shared screen stays the moment everyone finds out together.
        out.add(Outbound.to(c.playerId(), new ServerMessage.AnswerAck(
                c.questionIndex(), true, ServerMessage.AnswerAck.Reason.OK)));

        if (state.everyoneAnswered()) {
            out.addAll(closeQuestion());
        }
        return out;
    }

    private List<Outbound> onTimerExpired(RoomCommand.TimerExpired c) {
        // A timer that fires for an already-closed question must not close
        // the next one.
        if (state.phase() != RoomPhase.QUESTION_ACTIVE || c.questionIndex() != state.currentIndex()) {
            return List.of();
        }
        return closeQuestion();
    }

    /** Locks the question and reveals. This is the only place the answer goes out. */
    private List<Outbound> closeQuestion() {
        state.phase(RoomPhase.REVEAL);

        int index = state.currentIndex();
        Quiz.Item item = state.currentItem();
        List<Integer> distribution = state.distribution(index);

        List<Outbound> out = new ArrayList<>();
        for (RoomState.Player player : state.contenders()) {
            AnswerEvent event = state.eventsFor(index).stream()
                    .filter(e -> e.playerId().equals(player.id()))
                    .findFirst()
                    .orElse(null);

            out.add(Outbound.to(player.id(), new ServerMessage.Reveal(
                    index,
                    item.correctIndex(),
                    distribution,
                    event == null ? null : event.choiceIndex(),
                    event == null ? Boolean.FALSE : event.correct(),
                    event == null ? 0 : event.pointsAwarded(),
                    player.streak(),
                    player.score())));
        }

        // The host screen gets the distribution without per-player fields.
        out.add(Outbound.host(new ServerMessage.Reveal(
                index, item.correctIndex(), distribution, null, null, null, null, null)));
        return out;
    }

    private List<Outbound> showScoreboard() {
        state.phase(RoomPhase.SCOREBOARD);

        List<RoomState.Player> ranked = state.ranked();
        List<ServerMessage.Scoreboard.Entry> top = new ArrayList<>();
        for (int i = 0; i < Math.min(10, ranked.size()); i++) {
            RoomState.Player p = ranked.get(i);
            int rank = i + 1;
            Integer delta = p.lastRank() == 0 ? null : p.lastRank() - rank;
            top.add(new ServerMessage.Scoreboard.Entry(
                    rank, p.id(), p.nickname(), p.score(), p.avatarId(), p.streak(), delta));
        }

        List<Outbound> out = new ArrayList<>();
        for (RoomState.Player p : ranked) {
            int rank = state.rankOf(p.id());
            Integer delta = p.lastRank() == 0 ? null : p.lastRank() - rank;
            out.add(Outbound.to(p.id(), new ServerMessage.Scoreboard(
                    top, rank, p.score(), delta)));
        }
        out.add(Outbound.host(new ServerMessage.Scoreboard(top, 1, 0, null)));

        // Remember positions so the next scoreboard can show movement.
        ranked.forEach(p -> p.lastRank(state.rankOf(p.id())));
        return out;
    }

    private List<Outbound> finish() {
        state.phase(RoomPhase.ENDED);
        return List.of();
    }
}
