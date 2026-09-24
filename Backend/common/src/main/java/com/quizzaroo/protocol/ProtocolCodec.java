package com.quizzaroo.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Turns protocol messages into wire frames and back.
 *
 * Every frame is an envelope: { "t": TYPE, "seq": N, "d": { payload } }.
 * Keeping encode/decode behind this one class is what lets the transport
 * swap from JSON to MessagePack later without touching game logic.
 *
 * Jackson handles records natively, so the message types stay annotation-free.
 * NON_NULL inclusion matters: optional fields must be absent rather than null,
 * because the schema sets additionalProperties:false and types every field.
 */
public final class ProtocolCodec {

    private final ObjectMapper mapper;

    public ProtocolCodec() {
        this(new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL));
    }

    public ProtocolCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    // ---------- encoding ----------

    /** Serializes just the payload, without the envelope. Used by tests. */
    public JsonNode payloadOf(Object message) {
        return mapper.valueToTree(message);
    }

    public String encode(long seq, ClientMessage message) {
        return writeEnvelope(message.type(), seq, message);
    }

    public String encode(long seq, ServerMessage message) {
        return writeEnvelope(message.type(), seq, message);
    }

    private String writeEnvelope(String type, long seq, Object payload) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("t", type);
        envelope.put("seq", seq);
        envelope.set("d", mapper.valueToTree(payload));
        try {
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new ProtocolException("Could not encode " + type, e);
        }
    }

    // ---------- decoding ----------

    /**
     * Decodes an inbound frame. Throws rather than returning null: a frame
     * that cannot be decoded is a protocol violation, and the caller should
     * answer with an Error and close, not carry on with a null.
     */
    public ClientMessage decodeClient(String frame) {
        JsonNode envelope = readTree(frame);
        String type = requireText(envelope, "t");
        JsonNode payload = envelope.get("d");
        if (payload == null || !payload.isObject()) {
            throw new ProtocolException("Frame has no object payload in 'd'");
        }

        return switch (type) {
            case ClientMessage.Join.TYPE       -> convert(payload, ClientMessage.Join.class);
            case ClientMessage.ClockSync.TYPE  -> convert(payload, ClientMessage.ClockSync.class);
            case ClientMessage.Answer.TYPE     -> convert(payload, ClientMessage.Answer.class);
            case ClientMessage.HostAction.TYPE -> convert(payload, ClientMessage.HostAction.class);
            default -> throw new ProtocolException("Unknown client message type: " + type);
        };
    }

    /** Decodes an outbound frame. Used by the load-test bot client. */
    public ServerMessage decodeServer(String frame) {
        JsonNode envelope = readTree(frame);
        String type = requireText(envelope, "t");
        JsonNode payload = envelope.get("d");
        if (payload == null || !payload.isObject()) {
            throw new ProtocolException("Frame has no object payload in 'd'");
        }

        return switch (type) {
            case ServerMessage.Joined.TYPE       -> convert(payload, ServerMessage.Joined.class);
            case ServerMessage.ClockSyncAck.TYPE -> convert(payload, ServerMessage.ClockSyncAck.class);
            case ServerMessage.Question.TYPE     -> convert(payload, ServerMessage.Question.class);
            case ServerMessage.AnswerAck.TYPE    -> convert(payload, ServerMessage.AnswerAck.class);
            case ServerMessage.Reveal.TYPE       -> convert(payload, ServerMessage.Reveal.class);
            case ServerMessage.Scoreboard.TYPE   -> convert(payload, ServerMessage.Scoreboard.class);
            case ServerMessage.PlayerJoined.TYPE -> convert(payload, ServerMessage.PlayerJoined.class);
            case ServerMessage.PlayerLeft.TYPE   -> convert(payload, ServerMessage.PlayerLeft.class);
            case ServerMessage.Error.TYPE        -> convert(payload, ServerMessage.Error.class);
            default -> throw new ProtocolException("Unknown server message type: " + type);
        };
    }

    /** The envelope's sequence number, read without decoding the payload. */
    public long seqOf(String frame) {
        JsonNode envelope = readTree(frame);
        JsonNode seq = envelope.get("seq");
        if (seq == null || !seq.isIntegralNumber()) {
            throw new ProtocolException("Frame has no integer 'seq'");
        }
        return seq.asLong();
    }

    private JsonNode readTree(String frame) {
        try {
            return mapper.readTree(frame);
        } catch (Exception e) {
            throw new ProtocolException("Frame is not valid JSON", e);
        }
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new ProtocolException("Frame has no text field '" + field + "'");
        }
        return value.asText();
    }

    private <T> T convert(JsonNode payload, Class<T> target) {
        try {
            return mapper.treeToValue(payload, target);
        } catch (Exception e) {
            throw new ProtocolException("Could not decode payload as " + target.getSimpleName(), e);
        }
    }

    /** Thrown on any malformed or unknown frame. */
    public static final class ProtocolException extends RuntimeException {
        public ProtocolException(String message) { super(message); }
        public ProtocolException(String message, Throwable cause) { super(message, cause); }
    }
}
