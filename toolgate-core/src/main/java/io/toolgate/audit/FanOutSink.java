package io.toolgate.audit;

import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.AuditSink;
import java.util.List;

/**
 * Gives every event to each of several sinks, in order. A failing sink never keeps an event from the
 * others; once all have had it, the first failure is rethrown, with the rest suppressed, so the
 * dispatcher counts the event as dropped.
 */
public final class FanOutSink implements AuditSink {

    private final List<AuditSink> sinks;

    /**
     * Fans out to the given sinks.
     *
     * @param sinks the sinks, in delivery order; copied
     */
    public FanOutSink(List<AuditSink> sinks) {
        this.sinks = List.copyOf(sinks);
    }

    @Override
    public void emit(AuditEvent event) {
        RuntimeException firstFailure = null;
        for (AuditSink sink : sinks) {
            try {
                sink.emit(event);
            } catch (RuntimeException failed) {
                if (firstFailure == null) {
                    firstFailure = failed;
                } else {
                    firstFailure.addSuppressed(failed);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
