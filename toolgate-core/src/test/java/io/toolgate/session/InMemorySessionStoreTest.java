package io.toolgate.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.ToolId;
import io.toolgate.spi.SessionEvent.CallDecided;
import io.toolgate.spi.SessionEvent.CallDecided.Outcome;
import io.toolgate.spi.SessionState;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemorySessionStoreTest {

    private final InMemorySessionStore store = new InMemorySessionStore();

    @Test
    void unknownSessionIsEmpty() {
        assertThat(store.state("s1")).isEqualTo(SessionState.empty("s1"));
    }

    @Test
    void appendsAtTheExpectedVersion() {
        assertThat(store.append(decided("s1", Outcome.ALLOWED), 0)).isTrue();
        assertThat(store.append(decided("s1", Outcome.DENIED), 1)).isTrue();

        assertThat(store.state("s1")).isEqualTo(new SessionState("s1", 2, 1, 1));
    }

    @Test
    void rejectsAppendAfterAnotherWriter() {
        store.append(decided("s1", Outcome.ALLOWED), 0);

        assertThat(store.append(decided("s1", Outcome.ALLOWED), 0)).isFalse();
        assertThat(store.state("s1").toolCalls()).isEqualTo(1);
    }

    @Test
    void unconditionalAppendIgnoresTheVersion() {
        store.append(decided("s1", Outcome.ALLOWED), 0);

        store.append(decided("s1", Outcome.TERMINATED));

        assertThat(store.state("s1").version()).isEqualTo(2);
    }

    @Test
    void outcomesSpendTheRightBudget() {
        SessionState state = SessionState.empty("s1");
        for (Outcome outcome : Outcome.values()) {
            state = state.apply(decided("s1", outcome));
        }

        // Allowed, deferred and downgraded calls use tool-call budget; a denial uses denial budget.
        assertThat(state).isEqualTo(new SessionState("s1", 5, 3, 1));
    }

    private static CallDecided decided(String sessionId, Outcome outcome) {
        return new CallDecided(sessionId, "c", ToolId.local("t"), outcome, Instant.EPOCH);
    }
}
