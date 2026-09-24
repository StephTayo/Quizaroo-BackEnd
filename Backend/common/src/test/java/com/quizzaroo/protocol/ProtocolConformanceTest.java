package com.quizzaroo.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Holds the Java types and protocol/messages.schema.json to the same contract.
 *
 * The schema is the source of truth for both Java and TypeScript. Nothing
 * generates the Java types from it, so this test is what stops the two sides
 * drifting: rename a field on one side and the build fails here, rather than
 * a live room failing later.
 */
class ProtocolConformanceTest {

    private static ObjectMapper mapper;
    private static JsonNode schemaRoot;
    private static JsonSchemaFactory factory;
    private static ProtocolCodec codec;

    @BeforeAll
    static void loadSchema() throws Exception {
        codec = new ProtocolCodec();
        mapper = codec.mapper();
        factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

        try (InputStream in = ProtocolConformanceTest.class
                .getClassLoader()
                .getResourceAsStream("messages.schema.json")) {
            assertThat(in)
                    .as("messages.schema.json must be on the test classpath "
                            + "(see the sourceSets block in common/build.gradle.kts)")
                    .isNotNull();
            schemaRoot = mapper.readTree(in);
        }
    }

    /**
     * Builds a schema that validates against one definition, keeping $defs in
     * scope so internal $ref lookups still resolve.
     */
    private static JsonSchema schemaFor(String definitionName) {
        ObjectNode wrapper = schemaRoot.deepCopy();
        wrapper.put("$ref", "#/$defs/" + definitionName);
        return factory.getSchema(wrapper);
    }

    private static void assertConforms(String definitionName, Object message) {
        JsonNode payload = codec.payloadOf(message);
        Set<ValidationMessage> errors = schemaFor(definitionName).validate(payload);
        assertThat(errors)
                .as("%s payload must satisfy $defs/%s -- payload was %s",
                        message.getClass().getSimpleName(), definitionName, payload)
                .isEmpty();
    }

    // ---------- every message type, serialized and validated ----------

    static Stream<Arguments> allMessages() {
        return Stream.of(
                Arguments.of("Join", new ClientMessage.Join("482931", "Roo", "roo-3", null)),
                Arguments.of("Join", new ClientMessage.Join("482931", "Roo", null, "tok-abc")),
                Arguments.of("ClockSync", new ClientMessage.ClockSync(1_700_000_000_000L)),
                Arguments.of("Answer", new ClientMessage.Answer(2, 1, 1_700_000_000_000L)),
                Arguments.of("HostAction",
                        new ClientMessage.HostAction(ClientMessage.HostAction.Action.START, null)),
                Arguments.of("HostAction",
                        new ClientMessage.HostAction(ClientMessage.HostAction.Action.KICK, "p-77")),

                Arguments.of("Joined", new ServerMessage.Joined(
                        "p-1", "tok-abc", RoomPhase.LOBBY, 23, "Capital Cities", null)),
                Arguments.of("Joined", new ServerMessage.Joined(
                        "p-1", "tok-abc", RoomPhase.QUESTION_ACTIVE, 23, "Capital Cities", true)),
                Arguments.of("ClockSyncAck",
                        new ServerMessage.ClockSyncAck(1_700_000_000_000L, 1_700_000_000_042L)),
                Arguments.of("Question", new ServerMessage.Question(
                        2, 12, "Which city is the capital of Canada?", null,
                        List.of(new ServerMessage.Question.Choice(0, "Toronto"),
                                new ServerMessage.Question.Choice(1, "Ottawa"),
                                new ServerMessage.Question.Choice(2, "Vancouver"),
                                new ServerMessage.Question.Choice(3, "Calgary")),
                        20_000L, 1_700_000_000_000L)),
                Arguments.of("AnswerAck", new ServerMessage.AnswerAck(
                        2, true, ServerMessage.AnswerAck.Reason.OK)),
                Arguments.of("AnswerAck", new ServerMessage.AnswerAck(
                        2, false, ServerMessage.AnswerAck.Reason.ALREADY_ANSWERED)),
                Arguments.of("Reveal", new ServerMessage.Reveal(
                        2, 1, List.of(4, 15, 2, 2), 1, true, 847, 3, 2_410)),
                Arguments.of("Scoreboard", new ServerMessage.Scoreboard(
                        List.of(new ServerMessage.Scoreboard.Entry(
                                1, "p-9", "Maya", 2_530, "roo-1", 4, 0)),
                        3, 2_410, 2)),
                Arguments.of("PlayerPresence",
                        new ServerMessage.PlayerJoined("p-2", "Sam", "roo-5", 24)),
                Arguments.of("PlayerPresence",
                        new ServerMessage.PlayerLeft("p-2", "Sam", "roo-5", 23)),
                Arguments.of("Error",
                        new ServerMessage.Error(ServerMessage.Error.ROOM_NOT_FOUND, "No such room"))
        );
    }

    @ParameterizedTest(name = "{0} conforms to schema")
    @MethodSource("allMessages")
    void messageConformsToSchema(String definitionName, Object message) {
        assertConforms(definitionName, message);
    }

    // ---------- envelope ----------

    @Test
    @DisplayName("encoded frames satisfy the Envelope schema")
    void envelopeConforms() throws Exception {
        String frame = codec.encode(7, new ClientMessage.Answer(2, 1, 1_700_000_000_000L));
        JsonNode envelope = mapper.readTree(frame);

        Set<ValidationMessage> errors = schemaFor("Envelope").validate(envelope);
        assertThat(errors).as("envelope %s", frame).isEmpty();
        assertThat(envelope.get("t").asText()).isEqualTo("ANSWER");
        assertThat(envelope.get("seq").asLong()).isEqualTo(7L);
    }

    // ---------- round trips ----------

    @Test
    @DisplayName("client messages survive a round trip")
    void clientRoundTrip() {
        ClientMessage original = new ClientMessage.Join("482931", "Roo", "roo-3", null);
        ClientMessage decoded = codec.decodeClient(codec.encode(1, original));
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("server messages survive a round trip")
    void serverRoundTrip() {
        ServerMessage original = new ServerMessage.Reveal(
                2, 1, List.of(4, 15, 2, 2), 1, true, 847, 3, 2_410);
        ServerMessage decoded = codec.decodeServer(codec.encode(4, original));
        assertThat(decoded).isEqualTo(original);
    }

    // ---------- the invariant that matters most ----------

    @Test
    @DisplayName("Question never carries the correct answer")
    void questionDoesNotLeakTheAnswer() {
        ServerMessage.Question question = new ServerMessage.Question(
                2, 12, "Which city is the capital of Canada?", null,
                List.of(new ServerMessage.Question.Choice(0, "Toronto"),
                        new ServerMessage.Question.Choice(1, "Ottawa")),
                20_000L, 1_700_000_000_000L);

        JsonNode payload = codec.payloadOf(question);
        assertThat(payload.has("correctIndex")).isFalse();
        assertThat(payload.has("correct")).isFalse();
        assertThat(payload.has("answer")).isFalse();
    }

    @Test
    @DisplayName("optional fields are omitted, not sent as null")
    void nullsAreOmitted() {
        JsonNode payload = codec.payloadOf(
                new ClientMessage.Join("482931", "Roo", null, null));
        assertThat(payload.has("avatarId")).isFalse();
        assertThat(payload.has("reconnectToken")).isFalse();
    }

    // ---------- malformed input ----------

    @Test
    @DisplayName("unknown and malformed frames are rejected")
    void badFramesAreRejected() {
        assertThatThrownBy(() -> codec.decodeClient("{\"t\":\"NOPE\",\"seq\":1,\"d\":{}}"))
                .isInstanceOf(ProtocolCodec.ProtocolException.class);
        assertThatThrownBy(() -> codec.decodeClient("not json"))
                .isInstanceOf(ProtocolCodec.ProtocolException.class);
        assertThatThrownBy(() -> codec.decodeClient("{\"seq\":1,\"d\":{}}"))
                .isInstanceOf(ProtocolCodec.ProtocolException.class);
    }
}
