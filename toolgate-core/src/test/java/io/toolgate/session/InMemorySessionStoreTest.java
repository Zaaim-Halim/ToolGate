package io.toolgate.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.api.Decision;
import io.toolgate.api.Label;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolId;
import io.toolgate.spi.SessionEvent.CallDecided;
import io.toolgate.spi.SessionEvent.CallReleased;
import io.toolgate.spi.SessionEvent.Declassified;
import io.toolgate.spi.SessionEvent.ResultRecorded;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.TaintSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        assertThat(store.state("s1")).isEqualTo(new SessionState("s1", 2, 1, 1, Map.of(), Map.of()));
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
        for (Decision applied : List.of(ALLOW, DENY, new Decision.Defer("r", "review", "s1:c"),
                new Decision.Downgrade("r", ToolId.local("draft"), ObjectValue.empty()), TERMINATE)) {
            state = state.apply(decided("s1", applied));
        }

        // Allowed, deferred and downgraded calls use tool-call budget; a denial uses denial budget.
        assertThat(state).isEqualTo(new SessionState("s1", 5, 3, 1, Map.of(), Map.of()));
    }

    @Test
    void taintKeepsTheFirstSourceOfEachLabel() {
        SessionState state = SessionState.empty("s1")
                .apply(result("s1/1", "readInvoice", Label.PRIVATE_DATA))
                .apply(result("s1/2", "fetchSupplierPage", Label.UNTRUSTED_CONTENT))
                .apply(result("s1/3", "readOtherInvoice", Label.PRIVATE_DATA));

        assertThat(state.taint()).containsExactly(
                Map.entry(Label.PRIVATE_DATA, new TaintSource("s1/1", ToolId.local("readInvoice"))),
                Map.entry(Label.UNTRUSTED_CONTENT, new TaintSource("s1/2", ToolId.local("fetchSupplierPage"))));
        assertThat(state.toolCalls()).as("results do not spend budget").isZero();
        assertThat(state.version()).isEqualTo(3);
    }

    @Test
    void declassifyRemovesOneLabelAndALaterSourceBringsItBack() {
        SessionState state = SessionState.empty("s1")
                .apply(result("s1/1", "fetch", Label.UNTRUSTED_CONTENT))
                .apply(new Declassified("s1", Label.UNTRUSTED_CONTENT, "parsed into a strict schema", Instant.EPOCH));

        assertThat(state.holds(Label.UNTRUSTED_CONTENT)).isFalse();
        assertThat(state.apply(result("s1/2", "fetch", Label.UNTRUSTED_CONTENT)).taint())
                .containsEntry(Label.UNTRUSTED_CONTENT, new TaintSource("s1/2", ToolId.local("fetch")));
    }

    /** Withheld output never reached the model: no taint, but a denial, so probing by search ends the run. */
    @Test
    void withheldResultCountsAsADenialAndAddsNoTaint() {
        SessionState state = SessionState.empty("s1")
                .apply(new ResultRecorded("s1", "s1/1", ToolId.local("searchInvoices"), true, true, Set.of(), Instant.EPOCH));

        assertThat(state.denials()).isEqualTo(1);
        assertThat(state.taint()).isEmpty();
    }

    @Test
    void allowedCallsAndReleasesAddToTotalsOthersDoNot() {
        Map<String, BigDecimal> refund = Map.of("issueRefund.amount", new BigDecimal("120.50"));
        SessionState state = SessionState.empty("s1")
                .apply(spending(ALLOW, refund))
                .apply(spending(DENY, refund))
                .apply(spending(new Decision.Defer("issueRefund.amount", "review", "s1:c"), refund))
                .apply(new CallReleased("s1", "s1:c", "c", Map.of("issueRefund.amount", new BigDecimal("300")), Instant.EPOCH));

        assertThat(state.total("issueRefund.amount")).isEqualByComparingTo("420.50");
        assertThat(state.total("other.amount")).isEqualByComparingTo("0");
        assertThat(state.toolCalls()).as("a release spends no budget twice").isEqualTo(2);
    }

    @Test
    void totalsNeverHoldNegativeAmounts() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new CallReleased("s1", "s1:c", "c", Map.of("issueRefund.amount", new BigDecimal("-1")), Instant.EPOCH));
    }

    private static CallDecided spending(Decision applied, Map<String, BigDecimal> spent) {
        return new CallDecided("s1", "c", "agent", Principal.service("svc", Tenant.none()), ToolId.local("issueRefund"),
                ObjectValue.empty(), applied, applied, spent, "sha256:test", Instant.EPOCH);
    }

    private static ResultRecorded result(String callId, String tool, Label... labels) {
        return new ResultRecorded("s1", callId, ToolId.local(tool), true, false, Set.of(labels), Instant.EPOCH);
    }

    private static final Decision ALLOW = new Decision.Allow("allow-list");
    private static final Decision DENY = new Decision.Deny("allow-list", "not listed");
    private static final Decision TERMINATE = new Decision.Terminate("budget.toolCalls", "used up");

    private static CallDecided decided(String sessionId, Decision applied) {
        return new CallDecided(sessionId, "c", "agent", Principal.service("svc", Tenant.none()), ToolId.local("t"),
                ObjectValue.empty(), applied, applied, Map.of(), "sha256:test", Instant.EPOCH);
    }
}
