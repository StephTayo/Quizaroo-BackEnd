package com.quizzaroo.room;

import com.quizzaroo.protocol.RoomPhase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mutable state of one room.
 *
 * Deliberately not thread-safe. Exactly one thread -- the room's writer --
 * ever touches this, so synchronisation here would be cost without benefit.
 * If you ever find yourself wanting a ConcurrentHashMap in this class, the
 * single-writer invariant has been broken somewhere upstream.
 */
public final class RoomState {

    private final String pin;
    private final Quiz quiz;
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final Map<String, String> playersByToken = new LinkedHashMap<>();
    private final List<AnswerEvent> events = new ArrayList<>();

    private RoomPhase phase = RoomPhase.LOBBY;
    private String hostPlayerId;
    private int currentIndex = -1;
    private long questionStartedAt;

    public RoomState(String pin, Quiz quiz) {
        this.pin = pin;
        this.quiz = quiz;
    }

    public String pin() { return pin; }
    public Quiz quiz() { return quiz; }
    public RoomPhase phase() { return phase; }
    public void phase(RoomPhase phase) { this.phase = phase; }

    public String hostPlayerId() { return hostPlayerId; }
    public void hostPlayerId(String id) { this.hostPlayerId = id; }
    public boolean isHost(String playerId) { return playerId != null && playerId.equals(hostPlayerId); }

    public int currentIndex() { return currentIndex; }
    public void currentIndex(int index) { this.currentIndex = index; }

    public long questionStartedAt() { return questionStartedAt; }
    public void questionStartedAt(long at) { this.questionStartedAt = at; }

    public Quiz.Item currentItem() { return quiz.item(currentIndex); }
    public boolean hasMoreQuestions() { return currentIndex + 1 < quiz.size(); }

    // ---------- players ----------

    public void addPlayer(Player player) {
        players.put(player.id(), player);
        playersByToken.put(player.token(), player.id());
    }

    public Player player(String id) { return players.get(id); }

    public Player playerByToken(String token) {
        String id = playersByToken.get(token);
        return id == null ? null : players.get(id);
    }

    public boolean nicknameTaken(String nickname) {
        return players.values().stream()
                .anyMatch(p -> p.nickname().equalsIgnoreCase(nickname));
    }

    public void removePlayer(String id) {
        Player removed = players.remove(id);
        if (removed != null) {
            playersByToken.remove(removed.token());
        }
    }

    /** Everyone currently in the room, host included. */
    public List<Player> players() { return new ArrayList<>(players.values()); }

    /** Players eligible to answer: connected, not the host, not spectating. */
    public List<Player> contenders() {
        return players.values().stream()
                .filter(p -> !p.spectator())
                .filter(p -> !isHost(p.id()))
                .toList();
    }

    public int playerCount() { return contenders().size(); }

    /** How many contenders have answered the current question. */
    public long answeredCurrent() {
        return contenders().stream()
                .filter(p -> p.answeredIndex() == currentIndex)
                .count();
    }

    public boolean everyoneAnswered() {
        List<Player> contenders = contenders();
        return !contenders.isEmpty() && answeredCurrent() == contenders.size();
    }

    // ---------- event stream ----------

    public void record(AnswerEvent event) { events.add(event); }

    public List<AnswerEvent> events() { return List.copyOf(events); }

    public List<AnswerEvent> eventsFor(int questionIndex) {
        return events.stream().filter(e -> e.questionIndex() == questionIndex).toList();
    }

    /** Count of answers per choice index for a question, for the reveal chart. */
    public List<Integer> distribution(int questionIndex) {
        int choiceCount = quiz.item(questionIndex).choices().size();
        int[] counts = new int[choiceCount];
        for (AnswerEvent e : eventsFor(questionIndex)) {
            if (e.choiceIndex() >= 0 && e.choiceIndex() < choiceCount) {
                counts[e.choiceIndex()]++;
            }
        }
        List<Integer> out = new ArrayList<>(choiceCount);
        for (int c : counts) {
            out.add(c);
        }
        return out;
    }

    /** Contenders ordered by score, highest first; ties broken by nickname. */
    public List<Player> ranked() {
        return contenders().stream()
                .sorted((a, b) -> {
                    int byScore = Integer.compare(b.score(), a.score());
                    return byScore != 0 ? byScore : a.nickname().compareToIgnoreCase(b.nickname());
                })
                .toList();
    }

    public int rankOf(String playerId) {
        List<Player> ranked = ranked();
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).id().equals(playerId)) {
                return i + 1;
            }
        }
        return ranked.size() + 1;
    }

    /**
     * One player in the room. Mutable for the same reason RoomState is: only
     * the room writer touches it.
     */
    public static final class Player {
        private final String id;
        private final String token;
        private final String nickname;
        private final String avatarId;

        private int score;
        private int streak;
        private boolean connected = true;
        private boolean spectator;
        private int answeredIndex = -1;
        private int lastRank;

        public Player(String id, String token, String nickname, String avatarId, boolean spectator) {
            this.id = id;
            this.token = token;
            this.nickname = nickname;
            this.avatarId = avatarId;
            this.spectator = spectator;
        }

        public String id() { return id; }
        public String token() { return token; }
        public String nickname() { return nickname; }
        public String avatarId() { return avatarId; }

        public int score() { return score; }
        public void addScore(int points) { this.score += points; }

        public int streak() { return streak; }
        public void streak(int streak) { this.streak = streak; }

        public boolean connected() { return connected; }
        public void connected(boolean connected) { this.connected = connected; }

        public boolean spectator() { return spectator; }
        public void spectator(boolean spectator) { this.spectator = spectator; }

        public int answeredIndex() { return answeredIndex; }
        public void answeredIndex(int index) { this.answeredIndex = index; }

        public int lastRank() { return lastRank; }
        public void lastRank(int rank) { this.lastRank = rank; }
    }
}
