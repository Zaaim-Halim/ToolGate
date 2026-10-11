package io.toolgate.api;

import static io.toolgate.api.Label.EXTERNAL_SINK;
import static io.toolgate.api.Label.PRIVATE_DATA;
import static io.toolgate.api.Label.UNTRUSTED_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.InvalidPolicyException;
import io.toolgate.api.policy.TaintAction;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.audit.AuditJson;
import io.toolgate.queue.InMemoryApprovalQueue;
import io.toolgate.session.InMemorySessionStore;
import io.toolgate.spi.ApprovalListener;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.PendingCall;
import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.SessionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Approving deferred calls: the re-check, at-most-once release, expiry and who may approve. */
class ApprovalTest {

    private static final Principal JOB = Principal.service("refund-agent", Tenant.of("acme"));
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");
    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"), "support");
    private static final Principal ROOT = Principal.user("root", Tenant.of("operator"), "toolgate-admin");

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

    /** A release counts in the session, so totals and replay see it; a refused approval records nothing. */
    @Test
    void releaseIsRecordedInTheSession() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(gate, JOB);
        long before = sessions.state("refunds-refund-agent").version();

        gate.approve(approvalId, LENA, JOB);

        assertThat(sessions.state("refunds-refund-agent").version()).isEqualTo(before + 1);
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
        Principal mariaInFinance = Principal.user("maria", Tenant.of("acme"), "support", "finance");
        String approvalId = deferRefund(gate, MARIA);

        assertThat(gate.approve(approvalId, mariaInFinance, MARIA)).isInstanceOfSatisfying(Approval.NotPermitted.class,
                notPermitted -> assertThat(notPermitted.reason()).contains("its own call"));
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
    void onlyReviewerRolesMayApproveOrReject() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(gate, JOB);
        Principal support = Principal.user("omar", Tenant.of("acme"), "support");

        assertThat(gate.approve(approvalId, support, JOB)).isInstanceOfSatisfying(Approval.NotPermitted.class,
                notPermitted -> assertThat(notPermitted.reason()).contains("[finance]"));
        assertThat(gate.reject(approvalId, support, "not mine to reject")).isFalse();
        assertThat(gate.pending()).hasSize(1);
        assertThat(gate.approve(approvalId, LENA, JOB)).isInstanceOf(Approval.Released.class);
    }

    /** Fail closed: an agent that names no reviewers has calls nobody may approve; they can only expire. */
    @Test
    void agentWithoutReviewersCannotHaveCallsApproved() {
        ToolGate gate = gate(ToolGatePolicy.builder()
                .tool("readReturnRequest", t -> t.labels(UNTRUSTED_CONTENT).effect(Effect.READ))
                .agent("refund-agent", a -> a.allow("readReturnRequest", "issueRefund"))
                .build());
        String approvalId = deferRefund(gate, JOB);

        assertThat(gate.approve(approvalId, LENA, JOB)).isInstanceOfSatisfying(Approval.NotPermitted.class,
                notPermitted -> assertThat(notPermitted.reason()).contains("names no reviewers"));
        clock.advance(Duration.ofDays(2));
        assertThat(gate.sweep().expired()).isEqualTo(1);
    }

    @Test
    void reviewersMustBeValidAndChangeTheHash() {
        assertThatThrownBy(() -> refundPolicy(a -> a.reviewers(" ")))
                .isInstanceOf(InvalidPolicyException.class);
        assertThat(refundPolicy(a -> { }).hash())
                .isNotEqualTo(ToolGatePolicy.builder()
                        .tool("readReturnRequest", t -> t.labels(UNTRUSTED_CONTENT).effect(Effect.READ))
                        .tool("lookupCustomer", t -> t.labels(PRIVATE_DATA).effect(Effect.READ))
                        .tool("issueRefund", t -> t.labels(EXTERNAL_SINK))
                        .tool("ping", t -> t.effect(Effect.READ))
                        .agent("refund-agent", a -> a.allow("readReturnRequest", "lookupCustomer", "issueRefund")
                                .onTrifecta(TaintAction.BLOCK).reviewers("support-lead"))
                        .build().hash());
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
    void sweepExpiresOnlyCallsPastTheirExpiry() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        deferRefund(gate, JOB);
        clock.advance(Duration.ofHours(23));
        String fresh = deferRefund(gate, JOB, "c9");
        clock.advance(Duration.ofHours(2));

        assertThat(gate.sweep().expired()).isEqualTo(1);
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

    /** A reviewer of one customer must never read or decide another customer's calls. */
    @Test
    void aReviewerInAnotherTenantCanNeitherSeeNorDecide() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(gate, JOB);
        Principal globexFinance = Principal.user("gus", Tenant.of("globex"), "finance");

        assertThat(gate.pending(globexFinance)).isEmpty();
        assertThat(gate.approve(approvalId, globexFinance, JOB)).isInstanceOfSatisfying(Approval.NotPermitted.class,
                notPermitted -> assertThat(notPermitted.reason()).contains("not in the call's tenant"));
        assertThat(gate.reject(approvalId, globexFinance, "not yours")).isFalse();
        assertThat(gate.pending(LENA)).singleElement()
                .isEqualTo(new ApprovalItem(gate.pending().getFirst(), ApprovalAccess.REVIEWER, true));
    }

    @Test
    void aSuperAdminSeesAndDecidesEveryTenantAndIsAuditedAsOne() {
        ToolGate gate = gate(refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin")));
        String approvalId = deferRefund(gate, JOB);
        Principal admin = Principal.user("root", Tenant.of("operator"), "toolgate-admin");

        assertThat(gate.pending(admin)).singleElement().satisfies(item -> {
            assertThat(item.access()).isEqualTo(ApprovalAccess.SUPER_ADMIN);
            assertThat(item.mayDecide()).isTrue();
        });
        assertThat(gate.approve(approvalId, admin, JOB, "the customer escalated; finance is away")).isInstanceOf(Approval.Released.class);
        gate.close();

        assertThat(events).filteredOn(AuditEvent.ApprovalDecided.class::isInstance).singleElement().satisfies(event -> {
            assertThat(((AuditEvent.ApprovalDecided) event).access()).isEqualTo(ApprovalAccess.SUPER_ADMIN);
            assertThat(AuditJson.format(event)).contains("\"access\":\"super_admin\"");
        });
    }

    /** The account most worth stealing still cannot wave its own requests through. */
    @Test
    void aSuperAdminMayNeverApproveTheirOwnCall() {
        ToolGate gate = gate(refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin")));
        Principal admin = Principal.user("root", Tenant.of("acme"), "toolgate-admin");
        String approvalId = deferRefund(gate, admin);

        assertThat(gate.pending(admin)).singleElement().satisfies(item -> assertThat(item.mayDecide()).isFalse());
        assertThat(gate.approve(approvalId, admin, admin)).isInstanceOfSatisfying(Approval.NotPermitted.class,
                notPermitted -> assertThat(notPermitted.reason()).contains("its own call"));
        assertThat(gate.reject(approvalId, admin, "withdrawn")).isFalse();
    }

    @Test
    void anAuditorSeesEveryTenantAndDecidesNothing() {
        ToolGate gate = gate(refundPolicy(a -> { }, p -> p.auditors("compliance")));
        String approvalId = deferRefund(gate, JOB);
        Principal auditor = Principal.user("ada", Tenant.of("operator"), "compliance");

        assertThat(gate.pending(auditor)).singleElement()
                .isEqualTo(new ApprovalItem(gate.pending().getFirst(), ApprovalAccess.AUDITOR, false));
        assertThat(gate.approve(approvalId, auditor, JOB)).isInstanceOfSatisfying(Approval.NotPermitted.class,
                notPermitted -> assertThat(notPermitted.reason()).contains("not decide"));
        assertThat(gate.reject(approvalId, auditor, "no")).isFalse();
    }

    @Test
    void aRequesterSeesTheirOwnCallAndConfirmsItOnlyInAnInteractiveAgent() {
        ToolGate headless = gate(refundPolicy(a -> { }));
        deferRefund(headless, MARIA);
        assertThat(headless.pending(MARIA)).singleElement()
                .isEqualTo(new ApprovalItem(headless.pending().getFirst(), ApprovalAccess.REQUESTER, false));

        InMemoryApprovalQueue otherQueue = new InMemoryApprovalQueue();
        ToolGate interactive = ToolGate.builder(refundPolicy(a -> a.interactive(true))).approvalQueue(otherQueue)
                .sessionStore(new InMemorySessionStore()).clock(clock).noDefaultLog().build();
        deferRefund(interactive, MARIA);
        assertThat(interactive.pending(MARIA)).singleElement().satisfies(item -> {
            assertThat(item.access()).isEqualTo(ApprovalAccess.REQUESTER);
            assertThat(item.mayDecide()).isTrue();
        });
    }

    @Test
    void aViewerWithNoAccessSeesNothing() {
        ToolGate gate = gate(refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin").auditors("compliance")));
        deferRefund(gate, JOB);

        assertThat(gate.pending(Principal.user("omar", Tenant.of("acme"), "support"))).isEmpty();
    }

    @Test
    void superAdminsAndAuditorsMustBeValidAndChangeTheHashOnlyWhenSet() {
        assertThatThrownBy(() -> ToolGatePolicy.builder().superAdmins()).isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> ToolGatePolicy.builder().auditors(" ")).isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> ToolGatePolicy.builder().superAdmins("a").superAdmins("b"))
                .isInstanceOf(InvalidPolicyException.class).hasMessageContaining("twice");

        String plain = refundPolicy(a -> { }).hash();
        assertThat(refundPolicy(a -> { }, p -> { }).hash()).isEqualTo(plain);
        assertThat(refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin")).hash()).isNotEqualTo(plain);
        assertThat(refundPolicy(a -> { }, p -> p.auditors("compliance")).hash()).isNotEqualTo(plain);
    }

    @Test
    void aSuperAdminMustJustifyTheirApproval() {
        ToolGate gate = gate(refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin")));
        String approvalId = deferRefund(gate, JOB);

        assertThat(gate.approve(approvalId, ROOT, JOB)).isInstanceOfSatisfying(Approval.NotPermitted.class,
                notPermitted -> assertThat(notPermitted.reason()).contains("justify"));
        assertThat(gate.pending()).hasSize(1);
        assertThatIllegalArgumentException().isThrownBy(() -> gate.approve(approvalId, ROOT, JOB, " "));
    }

    /** Break-glass: one stolen super admin account releases nothing by itself. */
    @Test
    void underBreakGlassASuperAdminsApprovalWaitsForASecondPerson() {
        ToolGate gate = gate(refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin").superAdminConfirmation()));
        String approvalId = deferRefund(gate, JOB);
        Principal secondAdmin = Principal.user("sam", Tenant.of("operator"), "toolgate-admin");

        assertThat(gate.approve(approvalId, ROOT, JOB, "finance is away")).isInstanceOf(Approval.AwaitingConfirmation.class);
        assertThat(gate.approve(approvalId, ROOT, JOB, "again")).isInstanceOfSatisfying(Approval.NotPermitted.class,
                notPermitted -> assertThat(notPermitted.reason()).contains("another person"));
        assertThat(queue.get(approvalId).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);

        assertThat(gate.approve(approvalId, secondAdmin, JOB, "confirmed with root by phone")).isInstanceOf(Approval.Released.class);
        assertThat(queue.get(approvalId).orElseThrow().decisions()).extracting(ApprovalDecision::by).containsExactly("root", "sam");
        gate.close();
        assertThat(events).hasAtLeastOneElementOfType(AuditEvent.ApprovalRecorded.class);
    }

    @Test
    void underBreakGlassAReviewerOfTheTenantMayConfirmAndASuperAdminMayRejectAlone() {
        ToolGate gate = gate(refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin").superAdminConfirmation()));
        String confirmed = deferRefund(gate, JOB, "c2");
        String rejected = deferRefund(gate, JOB, "c3");

        gate.approve(confirmed, ROOT, JOB, "urgent");
        assertThat(gate.approve(confirmed, LENA, JOB, "checked the order")).isInstanceOf(Approval.Released.class);
        assertThat(gate.reject(rejected, ROOT, "duplicate of c2")).isTrue();
    }

    @Test
    void breakGlassNeedsSuperAdminsAndChangesTheHash() {
        assertThatThrownBy(() -> refundPolicy(a -> { }, ToolGatePolicy.Builder::superAdminConfirmation))
                .isInstanceOf(InvalidPolicyException.class).hasMessageContaining("needs superAdmins");
        assertThat(refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin").superAdminConfirmation()).hash())
                .isNotEqualTo(refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin")).hash());
    }

    @Test
    void eachDecisionIsKeptWithTheCall() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(gate, JOB);

        gate.approve(approvalId, LENA, JOB, "order checked");

        assertThat(queue.get(approvalId).orElseThrow().decisions()).singleElement().isEqualTo(new ApprovalDecision(
                ApprovalDecision.Kind.APPROVE, "lena", ApprovalAccess.REVIEWER, clock.instant(), "order checked"));
    }

    @Test
    void theRequesterOrASuperAdminMayCancelAPendingCallAndNobodyElse() {
        ToolGate gate = gate(refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin")));
        String mine = deferRefund(gate, MARIA, "c2");
        String jobs = deferRefund(gate, JOB, "c3");

        assertThat(gate.cancel(mine, LENA, "not hers to withdraw")).isFalse();
        assertThat(gate.cancel(mine, MARIA, "the customer called back")).isTrue();
        assertThat(gate.cancel(mine, MARIA, "twice")).isFalse();
        assertThat(gate.cancel(jobs, ROOT, "the order was refunded by hand")).isTrue();
        assertThat(queue.get(mine).orElseThrow().status()).isEqualTo(ApprovalStatus.CANCELLED);
        assertThat(gate.approve(mine, LENA, MARIA)).isEqualTo(new Approval.AlreadyDecided(ApprovalStatus.CANCELLED));
    }

    @Test
    void aReleasedCallIsClaimedOnceAndItsOutcomeRecorded() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(gate, JOB);
        gate.approve(approvalId, LENA, JOB);

        Optional<Approval.Released> claimed = gate.claim(approvalId, "node-1", Duration.ofMinutes(1));
        assertThat(claimed).hasValueSatisfying(released -> assertThat(released.idempotencyKey()).isEqualTo(approvalId));
        assertThat(gate.claim(approvalId, "node-2", Duration.ofMinutes(1))).isEmpty();
        assertThat(gate.finished(approvalId, "node-2", new ToolResult.Succeeded(ArgValue.text("ok")))).isFalse();

        assertThat(gate.finished(approvalId, "node-1", new ToolResult.Succeeded(ArgValue.text("refund R-1")))).isTrue();
        PendingCall done = queue.get(approvalId).orElseThrow();
        assertThat(done.status()).isEqualTo(ApprovalStatus.EXECUTED);
        assertThat(done.run()).hasValueSatisfying(run -> {
            assertThat(run.executor()).isEqualTo("node-1");
            assertThat(run.summary()).isEqualTo("returned \"refund R-1\"");
        });
        assertThat(gate.finished(approvalId, "node-1", new ToolResult.Failed("again"))).isFalse();
    }

    @Test
    void aFailedRunIsRecordedAsFailed() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(gate, JOB);
        gate.approve(approvalId, LENA, JOB);
        gate.claim(approvalId, "node-1", Duration.ofMinutes(1));

        gate.finished(approvalId, "node-1", new ToolResult.Failed("payment provider timed out"));

        assertThat(queue.get(approvalId).orElseThrow().status()).isEqualTo(ApprovalStatus.FAILED);
    }

    /** Nobody knows whether the refund went out, so nothing may send it again. */
    @Test
    void aLostLeaseBecomesUnknownAndIsNeverRunAgain() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String approvalId = deferRefund(gate, JOB);
        gate.approve(approvalId, LENA, JOB);
        gate.claim(approvalId, "node-1", Duration.ofMinutes(1));

        clock.advance(Duration.ofMinutes(2));
        assertThat(gate.sweep()).isEqualTo(new ToolGate.Sweep(0, 1));
        assertThat(queue.get(approvalId).orElseThrow().status()).isEqualTo(ApprovalStatus.UNKNOWN);
        assertThat(gate.claim(approvalId, "node-2", Duration.ofMinutes(1))).isEmpty();

        assertThat(gate.finished(approvalId, "node-1", new ToolResult.Succeeded(ArgValue.text("late")))).isTrue();
        assertThat(queue.get(approvalId).orElseThrow().status()).isEqualTo(ApprovalStatus.EXECUTED);
    }

    /** Nobody knows whether the first refund went out, so the agent may not send a second one. */
    @Test
    void anUnknownOutcomeBlocksTheSameToolInTheSessionUntilResolved() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String lost = loseOutcome(gate, "c2");

        Decision second = gate.check(call("c3", "issueRefund", JOB, Map.of("order", ArgValue.number(7732))));
        assertThat(second).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.ruleId()).isEqualTo(BuiltInRules.APPROVAL_UNRESOLVED));
        assertThat(gate.check(call("c4", "lookupCustomer", JOB, Map.of()))).isInstanceOf(Decision.Allow.class);
        assertThat(gate.check(new ToolCall("c5", JOB, "refund-agent", "another-session", ToolId.local("issueRefund"),
                ObjectValue.of(Map.of()), clock.instant()))).isNotInstanceOf(Decision.Deny.class);

        assertThat(gate.resolve(lost, LENA, true, "the payment provider shows refund R-1")).isTrue();
        assertThat(queue.get(lost).orElseThrow().status()).isEqualTo(ApprovalStatus.EXECUTED);
        assertThat(queue.get(lost).orElseThrow().decisions()).last()
                .satisfies(decision -> assertThat(decision.kind()).isEqualTo(ApprovalDecision.Kind.RESOLVE));
        assertThat(gate.check(call("c6", "issueRefund", JOB, Map.of("order", ArgValue.number(7732)))).ruleId())
                .isNotEqualTo(BuiltInRules.APPROVAL_UNRESOLVED);
    }

    @Test
    void anotherApprovedCallOfTheToolIsRefusedWhileOneIsUnresolved() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String waiting = deferRefund(gate, JOB, "c3");
        loseOutcome(gate, "c2");

        assertThat(gate.approve(waiting, LENA, JOB)).isInstanceOfSatisfying(Approval.Refused.class,
                refused -> assertThat(refused.reason()).contains(BuiltInRules.APPROVAL_UNRESOLVED));
    }

    @Test
    void aLateOutcomeFromTheExecutorLiftsTheBlock() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String lost = loseOutcome(gate, "c2");

        gate.finished(lost, "node-1", new ToolResult.Failed("provider timed out"));

        assertThat(sessions.state("refunds-refund-agent").unresolved()).isEmpty();
        assertThat(queue.get(lost).orElseThrow().status()).isEqualTo(ApprovalStatus.FAILED);
    }

    @Test
    void onlyAReviewerOfTheTenantOrASuperAdminMayResolve() {
        ToolGate gate = gate(refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin").auditors("compliance")));
        String lost = loseOutcome(gate, "c2");

        assertThat(gate.resolve(lost, JOB, true, "I am sure it ran")).isFalse();
        assertThat(gate.resolve(lost, Principal.user("ada", Tenant.of("operator"), "compliance"), true, "looks fine")).isFalse();
        assertThat(gate.resolve(lost, Principal.user("gus", Tenant.of("globex"), "finance"), true, "checked")).isFalse();
        assertThat(gate.resolve(lost, ROOT, false, "no refund at the provider")).isTrue();
        assertThat(queue.get(lost).orElseThrow().status()).isEqualTo(ApprovalStatus.FAILED);
        assertThat(gate.resolve(lost, LENA, true, "again")).isFalse();
    }

    /** If the session missed the resolution, resolving again lifts the block rather than leaving it forever. */
    @Test
    void resolvingAgainRepairsASessionThatMissedTheOutcome() {
        ToolGate gate = gate(refundPolicy(a -> { }));
        String lost = loseOutcome(gate, "c2");
        gate.resolve(lost, LENA, true, "ran");
        sessions.append(new SessionEvent.CallUnresolved("refunds-refund-agent", lost, ToolId.local("issueRefund"), clock.instant()));

        assertThat(gate.resolve(lost, LENA, true, "repair")).isTrue();
        assertThat(sessions.state("refunds-refund-agent").unresolved()).isEmpty();
    }

    @Test
    void anApprovalListenerHearsEveryStepOfACallsLife() {
        List<String> heard = new CopyOnWriteArrayList<>();
        ApprovalListener listener = new ApprovalListener() {
            @Override
            public void deferred(AuditEvent.Decided event) {
                heard.add("deferred " + event.callId());
            }

            @Override
            public void decided(AuditEvent.ApprovalDecided event) {
                heard.add("decided " + event.status());
            }

            @Override
            public void recorded(AuditEvent.ApprovalRecorded event) {
                heard.add("recorded " + event.decidedBy());
            }

            @Override
            public void finished(AuditEvent.ApprovalDecided event) {
                heard.add("finished " + event.status());
            }

            @Override
            public void superAdminBurst(AuditEvent.SuperAdminBurst event) {
                heard.add("burst");
            }
        };
        ToolGatePolicy policy = refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin").superAdminConfirmation());
        try (ToolGate gate = ToolGate.builder(policy).approvalQueue(queue).sessionStore(sessions).clock(clock)
                .noDefaultLog().approvalListener(listener).superAdminAlert(1, Duration.ofHours(1)).build()) {
            String approvalId = deferRefund(gate, JOB);
            gate.approve(approvalId, ROOT, JOB, "finance is away");
            gate.approve(approvalId, LENA, JOB, "confirmed");
            gate.claim(approvalId, "node-1", Duration.ofMinutes(1));
            gate.finished(approvalId, "node-1", new ToolResult.Succeeded(ArgValue.text("R-1")));
            gate.reject(deferRefund(gate, JOB, "c3"), ROOT, "duplicate");
        }

        assertThat(heard).containsExactly("deferred c2", "recorded root", "decided RELEASED", "finished EXECUTED",
                "deferred c3", "burst", "decided REJECTED");
    }

    @Test
    void aBurstOfSuperAdminDecisionsRaisesOneAlertPerWindow() {
        ToolGatePolicy policy = refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin"));
        try (ToolGate gate = ToolGate.builder(policy).approvalQueue(queue).sessionStore(sessions).clock(clock)
                .noDefaultLog().auditSink(events::add).superAdminAlert(2, Duration.ofHours(1)).build()) {
            for (int call = 2; call <= 5; call++) {
                gate.reject(deferRefund(gate, JOB, "c" + call), ROOT, "cleanup " + call);
                clock.advance(Duration.ofMinutes(1));
            }
        }

        assertThat(events).filteredOn(AuditEvent.SuperAdminBurst.class::isInstance).singleElement().satisfies(event -> {
            assertThat(((AuditEvent.SuperAdminBurst) event).decisions()).isEqualTo(3);
            assertThat(AuditJson.format(event)).contains("\"event\":\"toolgate.superadmin.burst\"").contains("\"lastBy\":\"root\"");
        });
    }

    @Test
    void theSuperAdminAlertIsOnByDefaultAndCanBeTurnedOff() {
        ToolGatePolicy policy = refundPolicy(a -> { }, p -> p.superAdmins("toolgate-admin"));
        try (ToolGate gate = ToolGate.builder(policy).approvalQueue(queue).sessionStore(sessions).clock(clock)
                .noDefaultLog().auditSink(events::add).noSuperAdminAlert().build()) {
            for (int call = 2; call <= 9; call++) {
                gate.reject(deferRefund(gate, JOB, "c" + call), ROOT, "cleanup " + call);
            }
        }
        assertThat(events).noneMatch(AuditEvent.SuperAdminBurst.class::isInstance);

        events.clear();
        try (ToolGate gate = gate(policy)) {
            for (int call = 10; call <= 15; call++) {
                gate.reject(deferRefund(gate, JOB, "c" + call), ROOT, "cleanup " + call);
            }
        }
        assertThat(events).filteredOn(AuditEvent.SuperAdminBurst.class::isInstance).hasSize(1);
    }

    @Test
    void everyOutcomeIsAudited() {
        try (ToolGate gate = gate(refundPolicy(a -> { }))) {
            gate.approve(deferRefund(gate, JOB, "c2"), LENA, JOB);
            gate.reject(deferRefund(gate, JOB, "c3"), LENA, "duplicate");
            deferRefund(gate, JOB, "c4");
            clock.advance(Duration.ofDays(2));
            gate.sweep();
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
        return refundPolicy(extra, policy -> { });
    }

    private static ToolGatePolicy refundPolicy(Consumer<ToolGatePolicy.AgentBuilder> extra,
                                               Consumer<ToolGatePolicy.Builder> policyWide) {
        ToolGatePolicy.Builder builder = ToolGatePolicy.builder();
        policyWide.accept(builder);
        return builder
                .tool("readReturnRequest", t -> t.labels(UNTRUSTED_CONTENT).effect(Effect.READ))
                .tool("lookupCustomer", t -> t.labels(PRIVATE_DATA).effect(Effect.READ))
                .tool("issueRefund", t -> t.labels(EXTERNAL_SINK))
                .tool("ping", t -> t.effect(Effect.READ))
                .agent("refund-agent", a -> {
                    a.allow("readReturnRequest", "lookupCustomer", "issueRefund").onTrifecta(TaintAction.BLOCK)
                            .reviewers("finance");
                    extra.accept(a);
                })
                .build();
    }

    /** Defers, approves and claims a refund, then lets its lease run out, so its outcome is unknown. */
    private String loseOutcome(ToolGate gate, String callId) {
        String approvalId = deferRefund(gate, JOB, callId);
        gate.approve(approvalId, LENA, JOB);
        gate.claim(approvalId, "node-1", Duration.ofMinutes(1));
        clock.advance(Duration.ofMinutes(2));
        assertThat(gate.sweep().unknown()).isEqualTo(1);
        return approvalId;
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
