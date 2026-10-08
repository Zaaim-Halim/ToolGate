package io.toolgate.queue.jdbc;

import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.OnViolation.DEFER;
import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.Approval;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.SessionStore;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the module is for: pending calls shared by every instance, and kept across restarts. */
class TwoInstancesTest {

    private static final Principal JOB = Principal.service("refund-agent", Tenant.of("acme"));
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");

    private final ToolGatePolicy policy = ToolGatePolicy.builder()
            .agent("refund-agent", a -> a.allow("issueRefund").reviewers("finance")
                    .rule("issueRefund.amount", atMost("1000"), atMost("200").otherwise(DEFER)))
            .build();
    private final H2 database = H2.withQueueTable();

    @Test
    void oneInstanceDefersAnotherApprovesAndTheFirstCannotReleaseAgain() {
        SessionStore sharedSessions = sharedSessionStore();
        ToolGate first = instance(sharedSessions);
        ToolGate second = instance(sharedSessions);

        Decision deferred = first.check(refund(480));
        String approvalId = ((Decision.Defer) deferred).approvalId();

        assertThat(second.pending()).extracting(pending -> pending.approvalId()).containsExactly(approvalId);
        assertThat(second.approve(approvalId, LENA, JOB)).isInstanceOf(Approval.Released.class);
        assertThat(first.approve(approvalId, LENA, JOB)).isEqualTo(new Approval.AlreadyDecided(ApprovalStatus.RELEASED));
    }

    @Test
    void pendingCallsSurviveARestart() {
        SessionStore sessions = sharedSessionStore();
        String approvalId = ((Decision.Defer) instance(sessions).check(refund(480))).approvalId();

        ToolGate afterRestart = instance(sessions);

        assertThat(afterRestart.pending()).singleElement().satisfies(pending -> {
            assertThat(pending.approvalId()).isEqualTo(approvalId);
            assertThat(pending.call().args().field("amount")).contains(ArgValue.number(480));
        });
        assertThat(afterRestart.approve(approvalId, LENA, JOB)).isInstanceOf(Approval.Released.class);
    }

    private ToolGate instance(SessionStore sessions) {
        return ToolGate.builder(policy)
                .approvalQueue(new JdbcApprovalQueue(database))
                .sessionStore(sessions)
                .auditSink(event -> { })
                .build();
    }

    /** Session state shared across instances; a JDBC or Redis session store plays this part in production. */
    private static SessionStore sharedSessionStore() {
        return new SharedSessions();
    }

    private static ToolCall refund(int amount) {
        return new ToolCall("c1", JOB, "refund-agent", "refunds", ToolId.local("issueRefund"),
                ObjectValue.of(Map.of("order", ArgValue.number(7731), "amount", ArgValue.number(amount))), Instant.EPOCH);
    }
}
