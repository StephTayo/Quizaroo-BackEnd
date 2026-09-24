package com.quizzaroo.server;

import java.util.ArrayList;
import java.util.List;

/** A Connection that records frames instead of writing them anywhere. */
public final class FakeConnection implements Connection {

    private final String id;
    private final List<String> sent = new ArrayList<>();
    private long latencyMs;
    private boolean open = true;

    public FakeConnection(String id) {
        this(id, 0L);
    }

    public FakeConnection(String id, long latencyMs) {
        this.id = id;
        this.latencyMs = latencyMs;
    }

    @Override public String id() { return id; }
    @Override public void send(String frame) { sent.add(frame); }
    @Override public void close() { open = false; }
    @Override public long latencyMs() { return latencyMs; }
    @Override public boolean isOpen() { return open; }

    public void latencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    public List<String> sent() { return List.copyOf(sent); }
    public int frameCount() { return sent.size(); }
    public void clear() { sent.clear(); }
}
