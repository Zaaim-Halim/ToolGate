package io.toolgate.engine;

import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.ToolCall;
import io.toolgate.api.policy.AgentPolicy;
import io.toolgate.api.policy.Binding;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.rules.Bindings;
import io.toolgate.rules.TaintConditions;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.ToolCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Runs evaluators stage by stage and combines their verdicts into one decision.
 *
 * <p>Combining follows deny over defer over downgrade over allow, with two refinements the policy
 * format relies on. A taint verdict is softened into a configured downgrade, because the safe tool
 * removes the risk the taint signals; an access verdict is not. And each verdict carries its rule's
 * own mode, so a monitored rule is recorded in the computed decision without affecting the
 * effective one.
 */
public final class DecisionEngine {

    private final ToolGatePolicy policy;
    private final List<Evaluator> evaluators;

    public DecisionEngine(ToolGatePolicy policy, List<Evaluator> evaluators) {
        this.policy = Objects.requireNonNull(policy, "policy");
        // A stable sort keeps the given order within a stage, so rule order is predictable.
        this.evaluators = evaluators.stream().sorted(Comparator.comparing(Evaluator::stage)).toList();
    }

    /** Evaluates one call against the session's state before it, knowing no tool definitions. */
    public Evaluation evaluate(ToolCall call, SessionState session) {
        return evaluate(call, session, tool -> Optional.empty());
    }

    /** Evaluates one call against the session's state before it and the tool definitions given. */
    public Evaluation evaluate(ToolCall call, SessionState session, ToolCatalog definitions) {
        Optional<AgentPolicy> agent = policy.agent(call.agentId());
        EvaluationContext context = agent.map(found -> new EvaluationContext(call, policy, found, session, definitions))
                .orElse(null);
        List<RuledVerdict> verdicts = context != null ? collectVerdicts(context) : unknownAgent(call);

        Decision computed = boundIfLifted(combine(verdicts, call), context);
        Decision effective = boundIfLifted(combine(verdicts.stream().filter(RuledVerdict::enforced).toList(), call), context);
        if (effective instanceof Decision.Allow && !(computed instanceof Decision.Allow)) {
            effective = new Decision.Allow(BuiltInRules.MODE_MONITOR);
        }
        return new Evaluation(effective, computed);
    }

    /**
     * An allow that the trifecta or irreversible rule would have held without the call's bindings names the
     * binding that let it through, so the audit says why a tainted call ran.
     */
    private static Decision boundIfLifted(Decision decision, EvaluationContext context) {
        if (context == null || !(decision instanceof Decision.Allow allow) || !allow.ruleId().equals(BuiltInRules.ALLOW_LIST)
                || !taintWouldHold(context)) {
            return decision;
        }
        List<Binding> holding = Bindings.holding(context);
        return holding.isEmpty() ? decision : new Decision.Allow(holding.getFirst().ruleId());
    }

    private static boolean taintWouldHold(EvaluationContext context) {
        return TaintConditions.trifecta(context) || TaintConditions.irreversible(context);
    }

    private List<RuledVerdict> unknownAgent(ToolCall call) {
        Mode mode = policy.modeFor(BuiltInRules.AGENT_KNOWN);
        if (mode == Mode.OFF) {
            return List.of();
        }
        var verdict = new Verdict.Deny("agent '" + call.agentId() + "' is not in the policy", Origin.ACCESS);
        return List.of(new RuledVerdict(BuiltInRules.AGENT_KNOWN, verdict, mode));
    }

    private List<RuledVerdict> collectVerdicts(EvaluationContext context) {
        var verdicts = new ArrayList<RuledVerdict>();
        for (Evaluator evaluator : evaluators) {
            Mode mode = policy.modeFor(evaluator.id());
            if (mode == Mode.OFF) {
                // Only a policy that is OFF as a whole gets here; single rules cannot be turned off.
                continue;
            }
            Verdict verdict = evaluateFailingClosed(evaluator, context);
            if (verdict instanceof Verdict.Pass) {
                continue;
            }
            var ruled = new RuledVerdict(evaluator.id(), verdict, mode);
            verdicts.add(ruled);
            if (ruled.enforced() && ruled.endsEvaluation()) {
                break;
            }
        }
        return verdicts;
    }

    private static Verdict evaluateFailingClosed(Evaluator evaluator, EvaluationContext context) {
        try {
            return Objects.requireNonNull(evaluator.evaluate(context), "evaluator returned null");
        } catch (RuntimeException failure) {
            // Fail closed: a rule that cannot decide must never let the call through.
            return new Verdict.Deny("rule failed to evaluate: " + failure, Origin.ACCESS);
        } catch (StackOverflowError overflow) {
            // Input nested deeper than the rule could follow. The overflow unwinds only this rule's own frames,
            // so the thread can go on; the run ends, since a model sending such input is not to be trusted
            // further. Other errors, such as OutOfMemoryError, propagate: the JVM may be in no state to decide.
            return new Verdict.Terminate("rule ran out of stack evaluating the call");
        }
    }

    private static Decision combine(List<RuledVerdict> verdicts, ToolCall call) {
        List<RuledVerdict> downgrades = verdicts.stream()
                .filter(ruled -> ruled.verdict() instanceof Verdict.Downgrade)
                .toList();
        long safeTools = downgrades.stream().map(ruled -> ((Verdict.Downgrade) ruled.verdict()).safeTool())
                .distinct().count();
        if (safeTools > 1) {
            return new Decision.Deny(downgrades.getFirst().ruleId(),
                    "rules downgrade " + call.tool() + " to different safe tools");
        }
        Optional<RuledVerdict> downgrade = downgrades.stream().findFirst();

        RuledVerdict strongest = null;
        for (RuledVerdict ruled : verdicts) {
            RuledVerdict softened = downgrade.filter(ignored -> ruled.softenable()).orElse(ruled);
            if (strongest == null || softened.severity() > strongest.severity()) {
                strongest = softened;
            }
        }
        return strongest == null ? new Decision.Allow(BuiltInRules.ALLOW_LIST) : toDecision(strongest, call);
    }

    private static Decision toDecision(RuledVerdict ruled, ToolCall call) {
        String ruleId = ruled.ruleId();
        return switch (ruled.verdict()) {
            case Verdict.Terminate terminate -> new Decision.Terminate(ruleId, terminate.reason());
            case Verdict.Deny deny -> new Decision.Deny(ruleId, deny.reason());
            case Verdict.Defer defer -> new Decision.Defer(ruleId, defer.reason(), Decision.Defer.approvalIdOf(call));
            case Verdict.Downgrade downgrade -> new Decision.Downgrade(ruleId, downgrade.safeTool(), call.args());
            case Verdict.Pass pass -> throw new IllegalStateException("passes are never collected");
        };
    }

    /** A verdict with the rule that gave it and the mode that rule runs in. */
    private record RuledVerdict(String ruleId, Verdict verdict, Mode mode) {

        boolean enforced() {
            return mode == Mode.ENFORCE;
        }

        /** Only verdicts no later rule can change stop evaluation; a taint deny may still be softened. */
        boolean endsEvaluation() {
            return switch (verdict) {
                case Verdict.Terminate terminate -> true;
                case Verdict.Deny deny -> deny.origin() == Origin.ACCESS;
                case Verdict.Pass pass -> false;
                case Verdict.Defer defer -> false;
                case Verdict.Downgrade downgrade -> false;
            };
        }

        boolean softenable() {
            return switch (verdict) {
                case Verdict.Deny deny -> deny.origin() == Origin.TAINT;
                case Verdict.Defer defer -> defer.origin() == Origin.TAINT;
                case Verdict.Pass pass -> false;
                case Verdict.Downgrade downgrade -> false;
                case Verdict.Terminate terminate -> false;
            };
        }

        int severity() {
            return switch (verdict) {
                case Verdict.Pass pass -> 0;
                case Verdict.Downgrade downgrade -> 1;
                case Verdict.Defer defer -> 2;
                case Verdict.Deny deny -> 3;
                case Verdict.Terminate terminate -> 4;
            };
        }
    }

}
