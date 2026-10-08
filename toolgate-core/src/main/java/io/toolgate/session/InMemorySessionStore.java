package io.toolgate.session;

import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.SessionStore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the latest snapshot of each session in memory. Correct for a single instance only.
 *
 * <p>Sessions are never evicted yet; expiry arrives with the retention settings.
 */
public final class InMemorySessionStore implements SessionStore {

    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    @Override
    public SessionState state(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state != null ? state : SessionState.empty(sessionId);
    }

    @Override
    public boolean append(SessionEvent event, long expectedVersion) {
        var appended = new AtomicBoolean();
        sessions.compute(event.sessionId(), (sessionId, current) -> {
            SessionState base = current != null ? current : SessionState.empty(sessionId);
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
        sessions.compute(event.sessionId(), (sessionId, current) ->
                (current != null ? current : SessionState.empty(sessionId)).apply(event));
    }
}
