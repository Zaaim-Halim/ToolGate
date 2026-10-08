package io.toolgate.audit;

import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.AuditSink;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Delivers audit events to one sink from one background thread, through a bounded queue.
 *
 * <p>A slow or failing sink never slows or weakens enforcement: when the queue is full, or the sink
 * throws, the event is dropped and counted, and the decision stands.
 */
public final class AsyncAuditDispatcher implements AuditChannel {

    /** Enough for a burst of tens of thousands of calls per second against a briefly slow sink. */
    public static final int DEFAULT_CAPACITY = 8_192;

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final BlockingQueue<Supplier<AuditEvent>> queue;
    private final AuditSink sink;
    private final AtomicLong dropped = new AtomicLong();
    // Submitters share the read lock; close takes the write lock, so nothing is queued after close
    // without being counted.
    private final ReadWriteLock closing = new ReentrantReadWriteLock();
    private volatile boolean closed;
    private final Thread worker;

    /**
     * Starts delivering to a sink.
     *
     * @param sink     the sink
     * @param capacity how many events may wait
     */
    public AsyncAuditDispatcher(AuditSink sink, int capacity) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.worker = Thread.ofVirtual().name("toolgate-audit").start(this::deliver);
    }

    @Override
    public void submit(Supplier<AuditEvent> event) {
        closing.readLock().lock();
        try {
            if (closed || !queue.offer(event)) {
                dropped.incrementAndGet();
            }
        } finally {
            closing.readLock().unlock();
        }
    }

    @Override
    public long dropped() {
        return dropped.get();
    }

    @Override
    public void close() {
        closing.writeLock().lock();
        try {
            closed = true;
        } finally {
            closing.writeLock().unlock();
        }
        try {
            if (!worker.join(CLOSE_TIMEOUT)) {
                worker.interrupt();
                worker.join(CLOSE_TIMEOUT);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        dropped.addAndGet(queue.size());
        queue.clear();
    }

    private void deliver() {
        try {
            while (!closed || !queue.isEmpty()) {
                Supplier<AuditEvent> next = queue.poll(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                if (next != null) {
                    emit(next);
                }
            }
        } catch (InterruptedException interrupted) {
            // Close timed out; whatever is still queued is counted as dropped.
            Thread.currentThread().interrupt();
        }
    }

    private void emit(Supplier<AuditEvent> next) {
        try {
            sink.emit(next.get());
        } catch (RuntimeException failed) {
            dropped.incrementAndGet();
        }
    }
}
