package io.toolgate.test;

import io.toolgate.api.Approval;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.Principal;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.ToolResult;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.OwnershipResolver;
import io.toolgate.spi.SessionStore;
import io.toolgate.spi.ToolCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plays tool calls through a real gate exactly as a compromised model would make them, with no model
 * and no framework: what a policy decides is then a plain assertion.
 *
 * <p>Each call is checked; when it runs, its result is recorded too, so taint builds up as in
 * production. Ownership answers, tool definitions, the principal, the clock and the session are all scripted.
 */
public final class ScriptedSession implements AutoCloseable {

    private final SessionStore sessionStore = SessionStore.inMemory();
    private final ApprovalQueue approvalQueue = ApprovalQueue.inMemory();
    private final ToolCatalog.Mutable catalog = ToolCatalog.inMemory();
    private ToolGate gate;
    private final String agentId;
    private final Map<String, String> tenants = new HashMap<>();
    private final Map<String, String> owners = new HashMap<>();
    private final List<AuditEvent> auditEvents = new CopyOnWriteArrayList<>();
    private final ScriptClock clock = new ScriptClock(Instant.parse("2026-01-01T00:00:00Z"));
    private Principal principal;
    private String sessionId;
    private int sessions;
    private int calls;
    private ScriptedCall unrecorded;
    private boolean ended;

    private ScriptedSession(ToolGatePolicy policy, String agentId, Principal principal) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.gate = deploy(policy);
        newSession();
    }

    private ToolGate deploy(ToolGatePolicy policy) {
        return ToolGate.builder(policy)
                .sessionStore(sessionStore)
                .approvalQueue(approvalQueue)
                .ownershipResolver(new ScriptedOwners())
                .toolCatalog(catalog)
                .auditSink(auditEvents::add)
                .clock(clock)
                .build();
    }

    /**
     * Replaces the policy as a deployment would, keeping sessions, pending approvals, ownership
     * answers and the clock: approvals made from now on are re-checked against the new policy.
     *
     * @param policy the new policy
     * @return this session
     */
    public ScriptedSession redeploy(ToolGatePolicy policy) {
        requireRunning();
        flush();
        ToolGate replaced = gate;
        gate = deploy(policy);
        replaced.close();
        return this;
    }

    /**
     * A scripted session for one agent of a policy.
     *
     * @param policy    the policy under test
     * @param agentId   the agent making the calls
     * @param principal who the agent acts for
     * @return the session
     */
    public static ScriptedSession of(ToolGatePolicy policy, String agentId, Principal principal) {
        return new ScriptedSession(policy, agentId, principal);
    }

    /**
     * Makes a call and returns ToolGate's decision. A call that runs has its result recorded, with the
     * output given to {@link ScriptedCall#returning}, or {@code "ok"} once the script moves on.
     *
     * @param tool the tool, as in a policy: {@code sendEmail} or {@code mcp:jira/search_issues}
     * @param args the arguments: Strings, numbers, Booleans, Maps, Collections or ArgValues
     * @return the call and its decision
     */
    public ScriptedCall call(String tool, Map<String, ?> args) {
        requireRunning();
        flush();
        var call = new ToolCall("c" + (++calls), principal, agentId, sessionId, ToolId.parse(tool), Args.object(args),
                clock.instant());
        var scripted = new ScriptedCall(this, call, gate.check(call));
        if (scripted.runs()) {
            unrecorded = scripted;
        }
        return scripted;
    }

    /**
     * Approves a deferred call as a reviewer would; a released call is run and its result recorded.
     *
     * @param deferred a call that was deferred
     * @param reviewer who approves
     * @return the outcome
     */
    public Approval approve(ScriptedCall deferred, Principal reviewer) {
        requireRunning();
        flush();
        if (!(deferred.decision() instanceof Decision.Defer defer)) {
            throw new IllegalStateException("only a deferred call can be approved; it was " + deferred.decision());
        }
        Approval approval = gate.approve(defer.approvalId(), reviewer, deferred.call().principal());
        if (approval instanceof Approval.Released released) {
            gate.recordResult(released.call(), new ToolResult.Succeeded(ScriptedCall.defaultOutput()));
        }
        return approval;
    }

    /**
     * Scripts who owns a record, for {@code sameTenant}.
     *
     * @param resourceType the type the policy names, such as {@code invoice}
     * @param id           the record's ID
     * @param tenant       the owning tenant
     * @return this session
     */
    public ScriptedSession tenantOf(String resourceType, String id, String tenant) {
        tenants.put(resourceType + "\u0000" + id, tenant);
        return this;
    }

    /**
     * Scripts who owns a record, for {@code ownedByPrincipal}.
     *
     * @param resourceType the type the policy names, such as {@code customer}
     * @param id           the record's ID
     * @param owner        the owning principal's ID
     * @return this session
     */
    public ScriptedSession ownerOf(String resourceType, String id, String owner) {
        owners.put(resourceType + "\u0000" + id, owner);
        return this;
    }

    /**
     * Scripts a tool's current definition, as a framework or MCP server lists it, for pinned tools.
     * Listing the tool again replaces it, as a server's update would.
     *
     * @param definition the definition
     * @return this session
     */
    public ScriptedSession listTool(ToolDefinition definition) {
        flush();
        catalog.put(definition);
        return this;
    }

    /**
     * Makes later calls for another principal, in the same session.
     *
     * @param newPrincipal who the agent acts for from now on
     * @return this session
     */
    public ScriptedSession as(Principal newPrincipal) {
        flush();
        this.principal = Objects.requireNonNull(newPrincipal, "principal");
        return this;
    }

    /**
     * Starts a fresh session: no taint, fresh budgets and totals.
     *
     * @return this session
     */
    public ScriptedSession newSession() {
        flush();
        sessionId = "scripted-" + (++sessions);
        return this;
    }

    /**
     * Moves the scripted clock, for approval expiry.
     *
     * @param duration how far
     * @return this session
     */
    public ScriptedSession advance(Duration duration) {
        flush();
        clock.advance(duration);
        return this;
    }

    /**
     * Every audit event of the script, in order. Audit is delivered on a background thread, so this
     * closes the gate to deliver them all, which ends the script: call it last.
     *
     * @return the events
     */
    public List<AuditEvent> auditEvents() {
        if (!ended) {
            flush();
            ended = true;
            gate.close();
        }
        return List.copyOf(auditEvents);
    }

    /**
     * The gate under test, for anything the script does not cover; after {@link #redeploy}, the new one.
     *
     * @return the gate
     */
    public ToolGate gate() {
        return gate;
    }

    @Override
    public void close() {
        gate.close();
    }

    private void requireRunning() {
        if (ended) {
            throw new IllegalStateException("auditEvents() ended this script; read the audit events last");
        }
    }

    void record(ScriptedCall call, ArgValue output) {
        requireRunning();
        if (!call.runs()) {
            throw new IllegalStateException("only a call that runs has a result; this one was " + call.decision());
        }
        if (call.recorded()) {
            throw new IllegalStateException("the result of this call is already recorded");
        }
        call.recorded(gate.recordResult(call.ran(), new ToolResult.Succeeded(output)));
        if (unrecorded == call) {
            unrecorded = null;
        }
    }

    void flush() {
        if (unrecorded != null) {
            record(unrecorded, ScriptedCall.defaultOutput());
        }
    }

    private final class ScriptedOwners implements OwnershipResolver {
        @Override
        public Optional<String> tenantOf(String resourceType, String id) {
            return Optional.ofNullable(tenants.get(resourceType + "\u0000" + id));
        }

        @Override
        public Optional<String> ownerOf(String resourceType, String id) {
            return Optional.ofNullable(owners.get(resourceType + "\u0000" + id));
        }
    }

    /** A clock the script moves. */
    private static final class ScriptClock extends Clock {

        private volatile Instant now;

        ScriptClock(Instant start) {
            this.now = start;
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
