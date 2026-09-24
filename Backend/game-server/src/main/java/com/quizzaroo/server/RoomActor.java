package com.quizzaroo.server;

import com.quizzaroo.protocol.ServerMessage;
import com.quizzaroo.room.GameRoom;
import com.quizzaroo.room.Outbound;
import com.quizzaroo.room.RoomCommand;
import com.quizzaroo.room.RoomState;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Owns one GameRoom and is the only thread allowed to touch it.
 *
 * Netty event-loop threads never call into the room. They decode a frame,
 * build a RoomCommand, and drop it in the mailbox. This actor's single
 * virtual thread drains that mailbox, applies each command, and hands the
 * resulting messages to the sink for delivery.
 *
 * That is the whole design. It buys three things:
 *   - No locks and no concurrent collections in the game logic.
 *   - Deterministic ordering: commands apply in arrival order, always.
 *   - A natural backpressure signal, since mailbox depth is observable.
 *
 * Mailbox depth is the metric to alert on. A room whose queue is growing is
 * a room about to miss its own timer, and it will show up as a late reveal
 * long before CPU or memory look unusual.
 */
public final class RoomActor implements AutoCloseable {

    /** Default mailbox size. At 200 players a full question is ~200 commands. */
    public static final int DEFAULT_CAPACITY = 2_048;

    private final String pin;
    private final GameRoom room;
    private final Consumer<Outbound> sink;
    private final ScheduledExecutorService scheduler;
    private final BlockingQueue<Task> mailbox;
    private final AtomicBoolean running = new AtomicBoolean();

    private Thread thread;
    private ScheduledFuture<?> questionTimer;
    private volatile int peakDepth;
    private volatile long droppedCommands;

    public RoomActor(String pin, GameRoom room, Consumer<Outbound> sink,
                     ScheduledExecutorService scheduler) {
        this(pin, room, sink, scheduler, DEFAULT_CAPACITY);
    }

    public RoomActor(String pin, GameRoom room, Consumer<Outbound> sink,
                     ScheduledExecutorService scheduler, int capacity) {
        this.pin = pin;
        this.room = room;
        this.sink = sink;
        this.scheduler = scheduler;
        this.mailbox = new ArrayBlockingQueue<>(capacity);
    }

    /** One queued command, with a future the caller may wait on. Tests do; production does not. */
    private record Task(RoomCommand command, CompletableFuture<List<Outbound>> result) {}

    public String pin() {
        return pin;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = Thread.ofVirtual()
                .name("room-" + pin)
                .start(this::loop);
    }

    /**
     * Queues a command. Returns a future that completes with whatever the
     * room emitted, once the actor has applied it.
     *
     * Never blocks. If the mailbox is full the command is dropped and the
     * future fails -- dropping one answer beats stalling a Netty event loop
     * that is serving hundreds of other connections.
     */
    public CompletableFuture<List<Outbound>> submit(RoomCommand command) {
        CompletableFuture<List<Outbound>> result = new CompletableFuture<>();
        if (!running.get()) {
            result.completeExceptionally(new IllegalStateException("Room " + pin + " is not running"));
            return result;
        }
        if (!mailbox.offer(new Task(command, result))) {
            droppedCommands++;
            result.completeExceptionally(new MailboxFullException(pin, mailbox.size()));
            return result;
        }
        int depth = mailbox.size();
        if (depth > peakDepth) {
            peakDepth = depth;
        }
        return result;
    }

    /**
     * Reads room state from the actor thread.
     *
     * The only safe way to look at a live room from outside: metrics, admin
     * screens and tests all go through here rather than reading RoomState
     * directly, which would break the single-writer rule.
     */
    public <T> CompletableFuture<T> query(Function<RoomState, T> read) {
        CompletableFuture<T> answer = new CompletableFuture<>();
        submit(new RoomCommand.ClockSync("__query__", 0L))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        answer.completeExceptionally(failure);
                    } else {
                        answer.complete(read.apply(room.state()));
                    }
                });
        return answer;
    }

    // ---------- the loop ----------

    private void loop() {
        while (running.get()) {
            Task task;
            try {
                task = mailbox.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (task.command() == null) {
                return; // shutdown signal
            }

            try {
                List<Outbound> out = room.handle(task.command());
                rescheduleTimers(out);
                deliver(out);
                task.result().complete(out);
            } catch (RuntimeException e) {
                // One bad command must not kill the room. The player who sent
                // it gets an error; everyone else plays on.
                task.result().completeExceptionally(e);
            }
        }
    }

    private void deliver(List<Outbound> out) {
        for (Outbound outbound : out) {
            try {
                sink.accept(outbound);
            } catch (RuntimeException e) {
                // A failed write to one connection is that connection's
                // problem, not the room's.
            }
        }
    }

    /**
     * Arms the question timer from the room's own output.
     *
     * The actor watches for a Question going out and schedules TimerExpired
     * for its deadline. The room stays free of scheduling concerns, and a
     * timer can only exist for a question that actually started.
     */
    private void rescheduleTimers(List<Outbound> out) {
        for (Outbound outbound : out) {
            if (outbound.message() instanceof ServerMessage.Question question) {
                cancelQuestionTimer();
                int index = question.questionIndex();
                questionTimer = scheduler.schedule(
                        () -> submit(new RoomCommand.TimerExpired(index)),
                        question.timeLimitMs(),
                        TimeUnit.MILLISECONDS);
                return;
            }
        }

        // A reveal means the question closed early -- everyone answered, or
        // the host locked it. The pending timer is now stale.
        for (Outbound outbound : out) {
            if (outbound.message() instanceof ServerMessage.Reveal) {
                cancelQuestionTimer();
                return;
            }
        }
    }

    private void cancelQuestionTimer() {
        if (questionTimer != null) {
            questionTimer.cancel(false);
            questionTimer = null;
        }
    }

    // ---------- metrics ----------

    /** Current mailbox depth. Export this; alert when it stays above zero. */
    public int mailboxDepth() {
        return mailbox.size();
    }

    public int peakMailboxDepth() {
        return peakDepth;
    }

    public long droppedCommands() {
        return droppedCommands;
    }

    public boolean isRunning() {
        return running.get();
    }

    // ---------- shutdown ----------

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        cancelQuestionTimer();
        mailbox.offer(new Task(null, new CompletableFuture<>()));
        if (thread != null) {
            thread.interrupt();
        }
        Task pending;
        while ((pending = mailbox.poll()) != null) {
            if (pending.command() != null) {
                pending.result().completeExceptionally(
                        new IllegalStateException("Room " + pin + " closed"));
            }
        }
    }

    /** Thrown when a command arrives at a room that cannot keep up. */
    public static final class MailboxFullException extends RuntimeException {
        public MailboxFullException(String pin, int depth) {
            super("Room " + pin + " mailbox is full at depth " + depth);
        }
    }
}
