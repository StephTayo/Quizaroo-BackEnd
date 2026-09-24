package com.quizzaroo.server;

import com.quizzaroo.protocol.RoomPhase;
import com.quizzaroo.protocol.ServerMessage;
import com.quizzaroo.room.Outbound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers recipient resolution and the fan-out rule.
 *
 * The encoder here counts calls, which is how the one-encode-many-writes
 * property is asserted rather than assumed. That property is the difference
 * between a reveal that takes 5ms and one that takes 300ms, so it gets a
 * test rather than a comment.
 */
class OutboundDispatcherTest {

    private ConnectionRegistry registry;
    private OutboundDispatcher dispatcher;
    private AtomicInteger encodes;

    private FakeConnection hostConn;
    private FakeConnection aliceConn;
    private FakeConnection bobConn;

    private static final String HOST = "p-host";
    private static final String ALICE = "p-alice";
    private static final String BOB = "p-bob";

    @BeforeEach
    void setUp() {
        registry = new ConnectionRegistry();
        encodes = new AtomicInteger();

        dispatcher = new OutboundDispatcher(registry, (seq, message) -> {
            encodes.incrementAndGet();
            return message.type() + "#" + seq;
        });

        hostConn = new FakeConnection("c-1");
        aliceConn = new FakeConnection("c-2", 40);
        bobConn = new FakeConnection("c-3", 220);

        registry.register(hostConn);
        registry.register(aliceConn);
        registry.register(bobConn);
        registry.bind("c-1", HOST);
        registry.bind("c-2", ALICE);
        registry.bind("c-3", BOB);
        registry.markHost(HOST);
    }

    private static ServerMessage.Reveal reveal() {
        return new ServerMessage.Reveal(0, 1, List.of(1, 2, 0, 0), 1, true, 900, 1, 900);
    }

    @Test
    @DisplayName("a player message reaches only that player")
    void directDelivery() {
        dispatcher.dispatch(Outbound.to(ALICE, reveal()));

        assertThat(aliceConn.frameCount()).isEqualTo(1);
        assertThat(bobConn.frameCount()).isZero();
        assertThat(hostConn.frameCount()).isZero();
    }

    @Test
    @DisplayName("a broadcast reaches everyone but is encoded once")
    void broadcastEncodesOnce() {
        dispatcher.dispatch(Outbound.all(reveal()));

        assertThat(hostConn.frameCount()).isEqualTo(1);
        assertThat(aliceConn.frameCount()).isEqualTo(1);
        assertThat(bobConn.frameCount()).isEqualTo(1);
        assertThat(encodes.get()).isEqualTo(1);
        assertThat(dispatcher.fanOutRatio()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("every connection in a broadcast receives the identical frame")
    void broadcastSharesTheSameFrame() {
        dispatcher.dispatch(Outbound.all(reveal()));

        String frame = aliceConn.sent().get(0);
        assertThat(bobConn.sent().get(0)).isEqualTo(frame);
        assertThat(hostConn.sent().get(0)).isEqualTo(frame);
    }

    @Test
    @DisplayName("allExcept skips the named player and still encodes once")
    void broadcastExcludingOne() {
        dispatcher.dispatch(Outbound.allExcept(ALICE,
                new ServerMessage.PlayerJoined(BOB, "Bob", "roo-2", 2)));

        assertThat(aliceConn.frameCount()).isZero();
        assertThat(bobConn.frameCount()).isEqualTo(1);
        assertThat(hostConn.frameCount()).isEqualTo(1);
        assertThat(encodes.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("host messages go to the host alone")
    void hostDelivery() {
        dispatcher.dispatch(Outbound.host(reveal()));

        assertThat(hostConn.frameCount()).isEqualTo(1);
        assertThat(aliceConn.frameCount()).isZero();
        assertThat(bobConn.frameCount()).isZero();
    }

    @Test
    @DisplayName("a message for a dropped socket is counted, not thrown")
    void undeliverableIsCounted() {
        registry.unregister("c-2");

        dispatcher.dispatch(Outbound.to(ALICE, reveal()));

        assertThat(dispatcher.undeliverable()).isEqualTo(1);
        assertThat(dispatcher.framesWritten()).isZero();
    }

    @Test
    @DisplayName("a closed connection is skipped during a broadcast")
    void closedConnectionsAreSkipped() {
        bobConn.close();

        dispatcher.dispatch(Outbound.all(reveal()));

        assertThat(bobConn.frameCount()).isZero();
        assertThat(aliceConn.frameCount()).isEqualTo(1);
        assertThat(dispatcher.framesWritten()).isEqualTo(2);
    }

    @Test
    @DisplayName("a reveal batch of per-player messages encodes once each")
    void perPlayerBatchIsNotBroadcast() {
        dispatcher.dispatch(List.of(
                Outbound.to(ALICE, reveal()),
                Outbound.to(BOB, reveal()),
                Outbound.host(reveal())));

        assertThat(encodes.get()).isEqualTo(3);
        assertThat(dispatcher.framesWritten()).isEqualTo(3);
    }

    @Test
    @DisplayName("sequence numbers advance per encoded frame")
    void sequenceAdvances() {
        dispatcher.dispatch(Outbound.all(reveal()));
        dispatcher.dispatch(Outbound.all(reveal()));

        assertThat(aliceConn.sent().get(0)).isEqualTo("REVEAL#1");
        assertThat(aliceConn.sent().get(1)).isEqualTo("REVEAL#2");
    }
}
