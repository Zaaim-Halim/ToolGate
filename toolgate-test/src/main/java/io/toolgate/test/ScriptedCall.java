package io.toolgate.test;

import static org.junit.jupiter.api.Assertions.fail;

import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.ResultDecision;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolId;
import java.util.Objects;
import java.util.Optional;

/**
 * One call a script made, with ToolGate's decision. Assertions fail with the decision and its reason,
 * so a failing test says what the policy did instead.
 */
public final class ScriptedCall {

    private final ScriptedSession session;
    private final ToolCall call;
    private final Decision decision;
    private ResultDecision result;

    ScriptedCall(ScriptedSession session, ToolCall call, Decision decision) {
        this.session = session;
        this.call = call;
        this.decision = decision;
    }

    /**
     * The call as checked.
     *
     * @return the call
     */
    public ToolCall call() {
        return call;
    }

    /**
     * What ToolGate decided.
     *
     * @return the decision
     */
    public Decision decision() {
        return decision;
    }

    /**
     * Records what the tool returned, for result rules. Only for a call that runs; without it, the
     * tool is taken to return {@code "ok"} once the script moves on.
     *
     * @param output what the tool returned, as a String, number, Boolean, Map, Collection or ArgValue
     * @return this call
     * @throws IllegalStateException if the call did not run, or its result is already recorded
     */
    public ScriptedCall returning(Object output) {
        session.record(this, Args.value(output));
        return this;
    }

    /**
     * Whether the model saw the output, once the result is recorded.
     *
     * @return the result decision; empty for a call that did not run, or before the script moved on
     */
    public Optional<ResultDecision> result() {
        return Optional.ofNullable(result);
    }

    /**
     * Asserts the tool runs as called.
     *
     * @return this call
     */
    public ScriptedCall assertAllowed() {
        return expect(decision instanceof Decision.Allow, "allowed");
    }

    /**
     * Asserts the call is denied.
     *
     * @return this call
     */
    public ScriptedCall assertDenied() {
        return expect(decision instanceof Decision.Deny, "denied");
    }

    /**
     * Asserts the call is denied by one rule.
     *
     * @param ruleId the rule, such as {@code readInvoice.id} or {@code taint.trifecta}
     * @return this call
     */
    public ScriptedCall assertDeniedBy(String ruleId) {
        return expect(decision instanceof Decision.Deny && decision.ruleId().equals(ruleId), "denied by " + ruleId);
    }

    /**
     * Asserts the call waits for review.
     *
     * @return this call
     */
    public ScriptedCall assertDeferred() {
        return expect(decision instanceof Decision.Defer, "deferred");
    }

    /**
     * Asserts a safe tool runs instead.
     *
     * @param safeTool the safe tool, as in a policy, such as {@code draftEmail}
     * @return this call
     */
    public ScriptedCall assertDowngradedTo(String safeTool) {
        return expect(decision instanceof Decision.Downgrade downgrade && downgrade.safeTool().equals(ToolId.parse(safeTool)),
                "downgraded to " + safeTool);
    }

    /**
     * Asserts the call ends the run.
     *
     * @return this call
     */
    public ScriptedCall assertTerminated() {
        return expect(decision instanceof Decision.Terminate, "the end of the run");
    }

    /**
     * Asserts the call ran but its output was withheld from the model. Records the result first if the
     * script has not.
     *
     * @return this call
     */
    public ScriptedCall assertWithheld() {
        session.flush();
        return expect(result instanceof ResultDecision.Withhold,
                "an output withheld from the model, but it was " + (result == null ? "never recorded" : "released"));
    }

    boolean runs() {
        return decision instanceof Decision.Allow || decision instanceof Decision.Downgrade;
    }

    boolean recorded() {
        return result != null;
    }

    void recorded(ResultDecision decided) {
        this.result = Objects.requireNonNull(decided, "decided");
    }

    /** The call that actually ran: the safe tool for a downgrade. */
    ToolCall ran() {
        return decision instanceof Decision.Downgrade downgrade ? call.withTool(downgrade.safeTool(), downgrade.safeArgs()) : call;
    }

    static ArgValue defaultOutput() {
        return ArgValue.text("ok");
    }

    private ScriptedCall expect(boolean holds, String expected) {
        if (!holds) {
            fail(call.tool() + " " + call.args().fields() + ": expected " + expected + ", but ToolGate decided " + decision);
        }
        return this;
    }
}
