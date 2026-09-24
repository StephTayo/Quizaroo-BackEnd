package com.quizzaroo.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Socket bookkeeping, including the reconnect case that decides whether a
 * player who locks their phone loses the game.
 */
class ConnectionRegistryTest {

    private ConnectionRegistry registry;
    private FakeConnection first;

    @BeforeEach
    void setUp() {
        registry = new ConnectionRegistry();
        first = new FakeConnection("c-1", 45);
        registry.register(first);
        registry.bind("c-1", "p-alice");
    }

    @Test
    @DisplayName("a bound socket resolves both ways")
    void bindingResolvesBothWays() {
        assertThat(registry.forPlayer("p-alice")).isSameAs(first);
        assertThat(registry.playerOf("c-1")).isEqualTo("p-alice");
        assertThat(registry.latencyOf("p-alice")).isEqualTo(45);
    }

    @Test
    @DisplayName("reconnecting swaps the socket and closes the stale one")
    void reconnectReplacesStaleSocket() {
        FakeConnection second = new FakeConnection("c-2", 60);
        registry.register(second);
        registry.bind("c-2", "p-alice");

        assertThat(first.isOpen()).isFalse();
        assertThat(registry.forPlayer("p-alice")).isSameAs(second);
        assertThat(registry.playerOf("c-1")).isNull();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("unregistering returns the player but leaves them in the game")
    void unregisterReturnsPlayer() {
        assertThat(registry.unregister("c-1")).isEqualTo("p-alice");
        assertThat(registry.forPlayer("p-alice")).isNull();
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("latency is zero when the socket is gone")
    void latencyDefaultsToZero() {
        registry.unregister("c-1");
        assertThat(registry.latencyOf("p-alice")).isZero();
    }

    @Test
    @DisplayName("an unjoined socket has no player")
    void unjoinedSocketHasNoPlayer() {
        registry.register(new FakeConnection("c-9"));
        assertThat(registry.playerOf("c-9")).isNull();
    }

    @Test
    @DisplayName("open() lists only live sockets")
    void openSkipsClosed() {
        FakeConnection second = new FakeConnection("c-2");
        registry.register(second);
        second.close();

        assertThat(registry.open()).containsExactly(first);
    }

    @Test
    @DisplayName("closeAll clears everything")
    void closeAllClears() {
        registry.closeAll();
        assertThat(registry.size()).isZero();
        assertThat(first.isOpen()).isFalse();
    }
}
