package io.toolgate.rules;

import io.toolgate.api.ArgValue;
import io.toolgate.api.NumberValue;
import io.toolgate.api.Principal;
import io.toolgate.api.ResultDecision;
import io.toolgate.api.Tenant;
import io.toolgate.api.TextValue;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolResult;
import io.toolgate.api.policy.AgentPolicy;
import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.api.policy.ResultCheck;
import io.toolgate.api.policy.ResultRule;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.OwnershipResolver;
import io.toolgate.support.Decimals;
import io.toolgate.validate.OutputPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies an agent's result rules to what a tool returned. Anything that cannot be checked, such as
 * an error, plain text or a missing field, is withheld: a result rule promises the model sees only
 * outputs that passed it.
 */
public final class ResultChecker {

    private final ToolGatePolicy policy;
    private final OwnershipResolver owners;
    private final Map<String, OutputPath> paths;

    /**
     * Compiles the policy's result rules.
     *
     * @param policy the policy
     * @param owners the application's ownership resolver
     */
    public ResultChecker(ToolGatePolicy policy, OwnershipResolver owners) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.paths = policy.agents().values().stream()
                .flatMap(agent -> agent.resultRules().stream())
                .map(ResultRule::path)
                .distinct()
                .collect(Collectors.toUnmodifiableMap(Function.identity(), OutputPath::parse));
    }

    /**
     * Checks one output.
     *
     * @param callThatRan the call whose tool ran
     * @param result      what it returned
     * @return what a fully enforced policy does with it
     */
    public ResultDecision check(ToolCall callThatRan, ToolResult result) {
        List<ResultRule> rules = policy.agent(callThatRan.agentId())
                .map(AgentPolicy::resultRules)
                .orElse(List.of())
                .stream()
                .filter(rule -> rule.tool().equals(callThatRan.tool()))
                .toList();
        for (ResultRule rule : rules) {
            Optional<String> problem = problem(rule, callThatRan.principal(), result);
            if (problem.isPresent()) {
                return new ResultDecision.Withhold(rule.ruleId(), problem.get());
            }
        }
        return ResultDecision.RELEASE;
    }

    private Optional<String> problem(ResultRule rule, Principal principal, ToolResult result) {
        if (!(result instanceof ToolResult.Succeeded(ArgValue output))) {
            return Optional.of("the tool failed, and its error cannot be checked");
        }
        Optional<List<ArgValue>> values = paths.get(rule.path()).select(output);
        if (values.isEmpty()) {
            return Optional.of("the output does not have the shape of " + rule.path());
        }
        var texts = new ArrayList<String>();
        for (ArgValue value : values.get()) {
            Optional<String> text = text(value);
            if (text.isEmpty()) {
                return Optional.of("a value at " + rule.path() + " is not a string or number");
            }
            texts.add(text.get());
        }
        try {
            // One lookup for every value the path reaches, so a list of N records costs one call to the resolver.
            Map<String, String> known = rule.check() instanceof ArgumentRule ownership
                    && !(ownership instanceof ArgumentRule.SameTenant && principal.tenant() instanceof Tenant.Unscoped)
                    ? OwnershipLookup.owners(owners, ownership, texts)
                    : Map.of();
            for (String text : texts) {
                Optional<String> problem = valueProblem(rule.check(), text, principal, known);
                if (problem.isPresent()) {
                    return Optional.of(rule.path() + ": " + problem.get());
                }
            }
        } catch (RuntimeException resolverFailure) {
            // Fail closed: a resolver that cannot answer must not let the output through.
            return Optional.of("ownership lookup failed: " + resolverFailure);
        }
        return Optional.empty();
    }

    private static Optional<String> valueProblem(ResultCheck check, String value, Principal principal,
                                                 Map<String, String> owners) {
        return switch (check) {
            case ResultCheck.MatchesTenant matches -> switch (principal.tenant()) {
                case Tenant.Scoped(String tenant) -> value.equals(tenant)
                        ? Optional.empty()
                        : Optional.of("'" + value + "' is not tenant " + tenant);
                case Tenant.Unscoped unscoped -> Optional.of("principal " + principal.id() + " has no tenant");
            };
            case ResultCheck.MatchesPrincipal matches -> value.equals(principal.id())
                    ? Optional.empty()
                    : Optional.of("'" + value + "' is not principal " + principal.id());
            case ArgumentRule.SameTenant(String type) -> switch (principal.tenant()) {
                case Tenant.Scoped(String tenant) -> Optional.ofNullable(owners.get(value))
                        .map(owner -> owner.equals(tenant)
                                ? Optional.<String>empty()
                                : Optional.of(type + " '" + value + "' belongs to tenant " + owner))
                        .orElseGet(() -> Optional.of(type + " '" + value + "' is unknown"));
                case Tenant.Unscoped unscoped -> Optional.of("principal " + principal.id() + " has no tenant");
            };
            case ArgumentRule.OwnedByPrincipal(String type) -> Optional.ofNullable(owners.get(value))
                    .map(owner -> owner.equals(principal.id())
                            ? Optional.<String>empty()
                            : Optional.of(type + " '" + value + "' belongs to " + owner))
                    .orElseGet(() -> Optional.of(type + " '" + value + "' is unknown"));
        };
    }

    private static Optional<String> text(ArgValue value) {
        return switch (value) {
            case TextValue(String text) -> Optional.of(text);
            case NumberValue(var number) -> Decimals.plainText(number);
            default -> Optional.empty();
        };
    }
}
