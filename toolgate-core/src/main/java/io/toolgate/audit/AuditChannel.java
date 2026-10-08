package io.toolgate.audit;

import io.toolgate.spi.AuditEvent;
import java.util.function.Supplier;

/** Where the gate sends audit events. Building an event is deferred, so it costs the caller nothing. */
public interface AuditChannel extends AutoCloseable {

    /** A channel that discards everything, for gates built without audit, such as in tests. */
    AuditChannel NONE = new AuditChannel() {
        @Override
        public void submit(Supplier<AuditEvent> event) {}

        @Override
        public long dropped() {
            return 0;
        }

        @Override
        public void close() {}
    };

    /**
     * Queues one event. Never blocks; an event that cannot be queued is counted as dropped.
     *
     * @param event builds the event, on another thread
     */
    void submit(Supplier<AuditEvent> event);

    /**
     * Events that never reached the sink.
     *
     * @return how many were dropped: queue full, sink failed, or submitted after close
     */
    long dropped();

    /** Stops accepting events and delivers those already queued, within a bounded time. */
    @Override
    void close();
}
