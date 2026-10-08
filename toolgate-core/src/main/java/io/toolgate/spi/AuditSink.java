package io.toolgate.spi;

/**
 * Receives audit events, such as a log, a SIEM or a database table.
 *
 * <p>Called from one background thread, one event at a time, never on the path of a tool call, so a
 * slow sink never slows a decision. An exception from a sink is counted as a dropped event and
 * never affects enforcement.
 */
@FunctionalInterface
public interface AuditSink {

    /**
     * Takes one event.
     *
     * @param event the event
     */
    void emit(AuditEvent event);
}
