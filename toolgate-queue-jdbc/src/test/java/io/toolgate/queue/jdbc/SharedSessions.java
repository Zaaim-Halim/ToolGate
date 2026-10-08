package io.toolgate.queue.jdbc;

import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.SessionStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** A session store built from the public SPI alone, standing in for one shared by several instances. */
final class SharedSessions implements SessionStore {

    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    @Override
    public SessionState state(String sessionId) {
        return sessions.getOrDefault(sessionId, SessionState.empty(sessionId));
    }

    @Override
    public boolean append(SessionEvent event, long expectedVersion) {
        var appended = new AtomicBoolean();
        sessions.compute(event.sessionId(), (id, current) -> {
            SessionState base = current != null ? current : SessionState.empty(id);
            if (base.version() != expectedVersion) {
                return current;
            }
            appended.set(true);
            return base.apply(event);
        });
        return appended.get();
    }

    @Override
    public void append(SessionEvent event) {
        sessions.compute(event.sessionId(), (id, current) -> (current != null ? current : SessionState.empty(id)).apply(event));
    }
}
