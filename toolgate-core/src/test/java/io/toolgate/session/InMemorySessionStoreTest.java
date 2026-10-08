package io.toolgate.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.Decision;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolId;
import io.toolgate.spi.SessionEvent.CallDecided;
import io.toolgate.spi.SessionState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemorySessionStoreTest {

    private final InMemorySessionStore store = new InMemorySessionStore();

    @Test
    void unknownSessionIsEmpty() {
        assertThat(store.state("s1")).isEqualTo(SessionState.empty("s1"));
    }

    @Test
    void appendsAtTheExpectedVersion() {
        assertThat(store.append(decided("s1", ALLOW), 0)).isTrue();
        assertThat(store.append(decided("s1", DENY), 1)).isTrue();

        assertThat(store.state("s1")).isEqualTo(new SessionState("s1", 2, 1, 1));
    }

    @Test
    void rejectsAppendAfterAnotherWriter() {
        store.append(decided("s1", ALLOW), 0);

        assertThat(store.append(decided("s1", ALLOW), 0)).isFalse();
        assertThat(store.state("s1").toolCalls()).isEqualTo(1);
    }

    @Test
    void unconditionalAppendIgnoresTheVersion() {
        store.append(decided("s1", ALLOW), 0);

        store.append(decided("s1", TERMINATE));

        assertThat(store.state("s1").version()).isEqualTo(2);
    }

    @Test
    void outcomesSpendTheRightBudget() {
        SessionState state = SessionState.empty("s1");
        for (Decision applied : List.of(ALLOW, DENY, new Decision.Defer("r", "review"),
                new Decision.Downgrade("r", ToolId.local("draft"), ObjectValue.empty()), TERMINATE)) {
            state = state.apply(decided("s1", applied));
        }

        // Allowed, deferred and downgraded calls use tool-call budget; a denial uses denial budget.
        assertThat(state).isEqualTo(new SessionState("s1", 5, 3, 1));
    }

    private static final Decision ALLOW = new Decision.Allow("allow-list");
    private static final Decision DENY = new Decision.Deny("allow-list", "not listed");
    private static final Decision TERMINATE = new Decision.Terminate("budget.toolCalls", "used up");

    private static CallDecided decided(String sessionId, Decision applied) {
        return new CallDecided(sessionId, "c", "agent", Principal.service("svc", Tenant.none()), ToolId.local("t"),
                ObjectValue.empty(), applied, applied, "sha256:test", Instant.EPOCH);
    }
}
