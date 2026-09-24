package com.quizzaroo.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is connected to one room, and which player each socket belongs to.
 *
 * A connection arrives before it has a player id -- the id only exists once
 * the room has accepted the join. So connections are registered first and
 * bound afterwards.
 *
 * Unlike RoomState this IS concurrent: sockets open and close on Netty
 * event-loop threads, which never touch the room itself.
 */
public final class ConnectionRegistry {

    private final Map<String, Connection> byConnectionId = new ConcurrentHashMap<>();
    private final Map<String, String> playerToConnection = new ConcurrentHashMap<>();
    private final Map<String, String> connectionToPlayer = new ConcurrentHashMap<>();

    private volatile String hostPlayerId;

    public void register(Connection connection) {
        byConnectionId.put(connection.id(), connection);
    }

    /** Ties a socket to a player once the room has accepted the join. */
    public void bind(String connectionId, String playerId) {
        String previous = playerToConnection.put(playerId, connectionId);
        connectionToPlayer.put(connectionId, playerId);

        // A reconnect replaces the old socket. Close the stale one so the
        // player is not holding two channels that both think they are live.
        if (previous != null && !previous.equals(connectionId)) {
            Connection stale = byConnectionId.remove(previous);
            connectionToPlayer.remove(previous);
            if (stale != null) {
                stale.close();
            }
        }
    }

    public void markHost(String playerId) {
        this.hostPlayerId = playerId;
    }

    public String hostPlayerId() {
        return hostPlayerId;
    }

    /** The player behind a socket, or null if it has not joined yet. */
    public String playerOf(String connectionId) {
        return connectionToPlayer.get(connectionId);
    }

    public Connection forPlayer(String playerId) {
        String connectionId = playerToConnection.get(playerId);
        return connectionId == null ? null : byConnectionId.get(connectionId);
    }

    public Connection forConnection(String connectionId) {
        return byConnectionId.get(connectionId);
    }

    /** Latency for a player, or 0 when the socket is gone or unmeasured. */
    public long latencyOf(String playerId) {
        Connection connection = forPlayer(playerId);
        return connection == null ? 0L : connection.latencyMs();
    }

    /**
     * Drops a socket. Returns the player it belonged to, or null.
     *
     * The player is not removed from the room -- a dropped socket is not a
     * departure, and their score has to survive for the reconnect to work.
     */
    public String unregister(String connectionId) {
        byConnectionId.remove(connectionId);
        String playerId = connectionToPlayer.remove(connectionId);
        if (playerId != null) {
            playerToConnection.remove(playerId, connectionId);
        }
        return playerId;
    }

    public List<Connection> open() {
        List<Connection> live = new ArrayList<>(byConnectionId.size());
        for (Connection connection : byConnectionId.values()) {
            if (connection.isOpen()) {
                live.add(connection);
            }
        }
        return live;
    }

    public int size() {
        return byConnectionId.size();
    }

    public void closeAll() {
        byConnectionId.values().forEach(Connection::close);
        byConnectionId.clear();
        playerToConnection.clear();
        connectionToPlayer.clear();
    }
}
