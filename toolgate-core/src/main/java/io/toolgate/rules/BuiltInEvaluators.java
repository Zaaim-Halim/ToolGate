package io.toolgate.rules;

import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.engine.Evaluator;
import io.toolgate.spi.HostResolver;
import io.toolgate.spi.OwnershipResolver;
import io.toolgate.validate.ResolvedAddresses;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** The evaluators every gate runs. */
public final class BuiltInEvaluators {

    /** Accepts every URL host name without resolving it. */
    public static final Function<String, Optional<String>> NO_NAME_CHECK = host -> Optional.empty();

    /** Knows no owners, so every ownership rule denies; for policies without ownership rules. */
    public static final OwnershipResolver NO_OWNERS = new OwnershipResolver() { };

    private BuiltInEvaluators() {}

    /**
     * A name check that resolves URL host names and denies names pointing at special-purpose addresses.
     *
     * @param resolver the application's resolver
     * @return the check
     */
    public static Function<String, Optional<String>> resolving(HostResolver resolver) {
        return host -> ResolvedAddresses.problem(resolver, host);
    }

    /**
     * Whether a policy has rules that need an {@link OwnershipResolver}.
     *
     * @param policy the policy
     * @return {@code true} if any agent uses sameTenant or ownedByPrincipal
     */
    public static boolean usesOwnership(ToolGatePolicy policy) {
        return policy.agents().values().stream().anyMatch(agent ->
                agent.argumentRules().values().stream().anyMatch(ArgumentChecks::isOwnership)
                        || agent.resultRules().stream().anyMatch(rule -> rule.check() instanceof ArgumentRule.SameTenant
                                || rule.check() instanceof ArgumentRule.OwnedByPrincipal));
    }

    /**
     * Every built-in evaluator, plus one for each argument and taint rule in the policy, with no name
     * resolution and no owners; for tests and policies without ownership rules.
     *
     * @param policy the policy whose rules to apply
     * @return the evaluators, built-ins first
     */
    public static List<Evaluator> forPolicy(ToolGatePolicy policy) {
        return forPolicy(policy, NO_NAME_CHECK, NO_OWNERS);
    }

    /**
     * Every built-in evaluator, plus one for each argument and taint rule in the policy.
     *
     * @param policy    the policy whose rules to apply
     * @param nameCheck why a URL host name must not be used; {@link #NO_NAME_CHECK} or {@link #resolving}
     * @param owners    the application's ownership resolver
     * @return the evaluators, built-ins first
     */
    public static List<Evaluator> forPolicy(ToolGatePolicy policy, Function<String, Optional<String>> nameCheck,
                                            OwnershipResolver owners) {
        var evaluators = new ArrayList<>(all());
        policy.agents().values().forEach(agent -> {
            agent.argumentRules().forEach((argument, rules) -> {
                if (ArgumentChecks.isOwnership(rules)) {
                    evaluators.add(new OwnershipEvaluator(agent.id(), argument, rules, owners));
                } else if (ArgumentChecks.isNumeric(rules)) {
                    evaluators.add(new NumericEvaluator(agent.id(), argument, rules));
                } else if (ArgumentChecks.isValidated(rules)) {
                    evaluators.add(new ValidatorEvaluator(agent.id(), argument, rules));
                } else {
                    evaluators.add(new ArgumentEvaluator(agent.id(), argument, ArgumentChecks.compile(rules, nameCheck)));
                }
            });
            agent.taintRules().forEach(rule -> evaluators.add(new TaintRuleEvaluator(agent.id(), rule)));
            agent.requiredRoles().forEach((tool, roles) -> evaluators.add(new RoleEvaluator(agent.id(), tool, roles)));
        });
        return List.copyOf(evaluators);
    }

    /** Every built-in evaluator, in order within each stage. */
    public static List<Evaluator> all() {
        return List.of(
                new DenialBudgetEvaluator(),
                new ToolCallBudgetEvaluator(),
                new McpServerEvaluator(),
                new AllowListEvaluator(),
                new TrifectaEvaluator(),
                new IrreversibleEvaluator());
    }
}
