package io.toolgate.api;

import static io.toolgate.api.Label.EXTERNAL_SINK;
import static io.toolgate.api.Label.PRIVATE_DATA;
import static io.toolgate.api.Label.UNTRUSTED_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.api.policy.TaintAction;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.audit.AuditJson;
import io.toolgate.queue.InMemoryApprovalQueue;
import io.toolgate.session.InMemorySessionStore;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.SessionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Approving deferred calls: the re-check, at-most-once release, expiry and who may approve. */
class ApprovalTest {

    private static final Principal JOB = Principal.service("refund-agent", Tenant.of("acme"));
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");
    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"), "support");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-08T10:02:00Z"));
    private final InMemoryApprovalQueue queue = new InMemoryApprovalQueue();
    private final SessionStore sessions = new InMemorySessionStore();
    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    /** Case F: a refund deferred at 10:02, a new policy at 12:30, an approval at 15:10, a double click at 15:11. */
    @Test
    void caseFApprovalUnderAPolicyThatChangedIsRefusedOnce() {
        ToolGate morning = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(morning, JOB);

        clock.set("2026-10-08T12:30:00Z");
        ToolGate afternoon = gate(refundPolicy(a -> a.requireRole("issueRefund", "refund-approver")));

        clock.set("2026-10-08T15:10:00Z");
        Approval first = afternoon.approve(approvalId, LENA, JOB);
        clock.set("2026-10-08T15:11:00Z");
        Approval second = afternoon.approve(approvalId, LENA, JOB);

        assertThat(first).isInstanceOfSatisfying(Approval.Refused.class,
                refused -> assertThat(refused.reason()).contains("issueRefund.roles"));
        assertThat(second).isEqualTo(new Approval.AlreadyDecided(ApprovalStatus.REFUSED));
    }

    @Test
    void approvalReleasesTheCallOnceWithAnIdempotencyKey() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(gate, JOB);

        Approval first = gate.approve(approvalId, LENA, JOB);
        Approval second = gate.approve(approvalId, LENA, JOB);

        assertThat(first).isInstanceOfSatisfying(Approval.Released.class, released -> {
            assertThat(released.idempotencyKey()).isEqualTo(approvalId);
            assertThat(released.call().tool()).isEqualTo(ToolId.local("issueRefund"));
            assertThat(released.call().args().field("amount")).contains(ArgValue.number(480));
        });
        assertThat(second).isEqualTo(new Approval.AlreadyDecided(ApprovalStatus.RELEASED));
        assertThat(gate.pending()).isEmpty();
    }

    /** T10: a role revoked between deferral and approval counts, because the application passes the principal as it is now. */
    @Test
    void revokedRoleRefusesTheRelease() {
        ToolGate gate = gate(refundPolicy(a -> a.requireRole("issueRefund", "refunds")));
        Principal withRole = Principal.service("refund-agent", Tenant.of("acme"));
        Principal granted = new Principal(withRole.kind(), withRole.id(), withRole.tenant(), Set.of("refunds"));
        String approvalId = deferRefund(gate, granted);

        assertThat(gate.approve(approvalId, LENA, withRole)).isInstanceOf(Approval.Refused.class);
    }

    /** T10: the session gained private data after the review, so now the trifecta objects, which the reviewer never saw. */
    @Test
    void newTaintAfterDeferralRefusesTheRelease() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(gate, JOB);
        ToolCall lookup = call("c3", "lookupCustomer", JOB, Map.of());
        gate.check(lookup);
        gate.recordResult(lookup, new ToolResult.Succeeded(ArgValue.text("customer record")));

        assertThat(gate.approve(approvalId, LENA, JOB)).isInstanceOfSatisfying(Approval.Refused.class,
                refused -> assertThat(refused.reason()).contains("taint.trifecta"));
    }

    /** The call was counted when deferred, so a budget the run used up afterwards does not end it. */
    @Test
    void usedUpBudgetDoesNotRefuseAnApprovedCall() {
        ToolGate gate = gate(refundPolicy(a -> a.allow("ping").budget(b -> b.toolCalls(3))));
        String approvalId = deferRefund(gate, JOB);
        gate.check(call("c3", "ping", JOB, Map.of()));
        assertThat(gate.check(call("c4", "ping", JOB, Map.of()))).isInstanceOf(Decision.Terminate.class);

        assertThat(gate.approve(approvalId, LENA, JOB)).isInstanceOf(Approval.Released.class);
    }

    @Test
    void headlessCallCannotBeApprovedByItsOwnPrincipal() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(gate, MARIA);

        assertThat(gate.approve(approvalId, MARIA, MARIA)).isInstanceOf(Approval.NotPermitted.class);
        assertThat(gate.pending()).hasSize(1);
        assertThat(gate.approve(approvalId, LENA, MARIA)).isInstanceOf(Approval.Released.class);
    }

    /** In an interactive agent, "defer to the user" means the signed-in user confirms. */
    @Test
    void interactiveCallIsConfirmedByItsOwnUser() {
        ToolGate gate = gate(refundPolicy(a -> a.interactive(true)));
        String approvalId = deferRefund(gate, MARIA);

        assertThat(gate.approve(approvalId, MARIA, MARIA)).isInstanceOf(Approval.Released.class);
    }

    @Test
    void expiredCallCannotBeApproved() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(gate, JOB);

        clock.advance(Duration.ofHours(24));

        assertThat(gate.pending()).isEmpty();
        assertThat(gate.approve(approvalId, LENA, JOB)).isInstanceOf(Approval.Expired.class);
        assertThat(gate.approve(approvalId, LENA, JOB)).isEqualTo(new Approval.AlreadyDecided(ApprovalStatus.EXPIRED));
    }

    @Test
    void expirePendingSweepsOnlyCallsPastTheirExpiry() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        deferRefund(gate, JOB);
        clock.advance(Duration.ofHours(23));
        String fresh = deferRefund(gate, JOB, "c9");
        clock.advance(Duration.ofHours(2));

        assertThat(gate.expirePending()).isEqualTo(1);
        assertThat(gate.pending()).extracting(pending -> pending.approvalId()).containsExactly(fresh);
    }

    @Test
    void rejectedCallStaysRejected() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(gate, JOB);

        assertThat(gate.reject(approvalId, LENA, "customer already refunded by phone")).isTrue();
        assertThat(gate.reject(approvalId, LENA, "again")).isFalse();
        assertThat(gate.approve(approvalId, LENA, JOB)).isEqualTo(new Approval.AlreadyDecided(ApprovalStatus.REJECTED));
    }

    @Test
    void misuseFailsLoudly() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(gate, JOB);

        assertThatIllegalArgumentException().isThrownBy(() -> gate.approve("refunds:nope", LENA, JOB));
        assertThatIllegalArgumentException().isThrownBy(() -> gate.approve(approvalId, LENA, MARIA))
                .withMessageContaining("not the call's principal");
    }

    @Test
    void everyOutcomeIsAudited() {
        try (ToolGate gate = gate(refundPolicy(a -> { }))) {
            gate.approve(deferRefund(gate, JOB, "c2"), LENA, JOB);
            gate.reject(deferRefund(gate, JOB, "c3"), LENA, "duplicate");
            deferRefund(gate, JOB, "c4");
            clock.advance(Duration.ofDays(2));
            gate.expirePending();
        }

        assertThat(events).filteredOn(AuditEvent.ApprovalDecided.class::isInstance)
                .extracting(event -> ((AuditEvent.ApprovalDecided) event).status())
                .containsExactly(ApprovalStatus.RELEASED, ApprovalStatus.REJECTED, ApprovalStatus.EXPIRED);
        assertThat(events).filteredOn(AuditEvent.ApprovalDecided.class::isInstance).first()
                .satisfies(event -> assertThat(AuditJson.format(event))
                        .contains("\"event\":\"toolgate.released\"").contains("\"decidedBy\":\"lena\""));
    }

    private ToolGate gate(ToolGatePolicy policy) {
        return ToolGate.builder(policy).approvalQueue(queue).sessionStore(sessions).clock(clock)
                .auditSink(events::add).build();
    }

    /**
     * Case F's refund agent: a return request is untrusted content, refunds are irreversible, so
     * after reading one, a refund waits for review. The refund also pays out, so it is a sink.
     */
    private static ToolGatePolicy refundPolicy(Consumer<ToolGatePolicy.AgentBuilder> extra) {
        return ToolGatePolicy.builder()
                .tool("readReturnRequest", t -> t.labels(UNTRUSTED_CONTENT).effect(Effect.READ))
                .tool("lookupCustomer", t -> t.labels(PRIVATE_DATA).effect(Effect.READ))
                .tool("issueRefund", t -> t.labels(EXTERNAL_SINK))
                .tool("ping", t -> t.effect(Effect.READ))
                .agent("refund-agent", a -> {
                    a.allow("readReturnRequest", "lookupCustomer", "issueRefund").onTrifecta(TaintAction.BLOCK);
                    extra.accept(a);
                })
                .build();
    }

    private String deferRefund(ToolGate gate, Principal principal) {
        return deferRefund(gate, principal, "c2");
    }

    private String deferRefund(ToolGate gate, Principal principal, String callId) {
        ToolCall read = call(callId + "-read", "readReturnRequest", principal, Map.of());
        gate.check(read);
        gate.recordResult(read, new ToolResult.Succeeded(ArgValue.text("please refund order 7731")));
        Decision decision = gate.check(call(callId, "issueRefund", principal,
                Map.of("order", ArgValue.number(7731), "amount", ArgValue.number(480))));
        assertThat(decision).isInstanceOf(Decision.Defer.class);
        return ((Decision.Defer) decision).approvalId();
    }

    private ToolCall call(String callId, String tool, Principal principal, Map<String, ArgValue> args) {
        return new ToolCall(callId, principal, "refund-agent", "refunds-" + principal.id(), ToolId.local(tool),
                ObjectValue.of(args), clock.instant());
    }

    /** A clock the test moves forward. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void set(String instant) {
            now = Instant.parse(instant);
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
