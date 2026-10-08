package io.toolgate.rules;

import io.toolgate.api.ArgValue;
import io.toolgate.api.ListValue;
import io.toolgate.api.NumberValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.TextValue;
import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.api.policy.ToolArgument;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.engine.Evaluator;
import io.toolgate.engine.Origin;
import io.toolgate.engine.Stage;
import io.toolgate.engine.Verdict;
import io.toolgate.support.Decimals;
import io.toolgate.spi.OwnershipResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies one agent's ownership rules to one ID argument: every record it names must belong to the
 * principal's tenant, or to the principal, as the application's resolver reports.
 */
final class OwnershipEvaluator implements Evaluator {

    private final String agentId;
    private final ToolArgument argument;
    private final List<ArgumentRule> rules;
    private final OwnershipResolver resolver;

    OwnershipEvaluator(String agentId, ToolArgument argument, List<ArgumentRule> rules, OwnershipResolver resolver) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.argument = Objects.requireNonNull(argument, "argument");
        this.rules = List.copyOf(rules);
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public String id() {
        return argument.ruleId();
    }

    @Override
    public Stage stage() {
        return Stage.OWNERSHIP;
    }

    @Override
    public Verdict evaluate(EvaluationContext context) {
        if (!context.agent().id().equals(agentId) || !context.call().tool().equals(argument.tool())) {
            return Verdict.PASS;
        }
        Optional<ArgValue> value = context.call().args().field(argument.name());
        if (value.isEmpty()) {
            return Verdict.PASS;
        }
        Optional<List<String>> ids = ids(value.get());
        if (ids.isEmpty()) {
            return deny("must be an ID or a list of IDs");
        }
        Principal principal = context.call().principal();
        for (String id : ids.get()) {
            for (ArgumentRule rule : rules) {
                Optional<String> problem = problem(rule, id, principal);
                if (problem.isPresent()) {
                    return deny(problem.get());
                }
            }
        }
        return Verdict.PASS;
    }

    private Optional<String> problem(ArgumentRule rule, String id, Principal principal) {
        return switch (rule) {
            case ArgumentRule.SameTenant(String type) -> switch (principal.tenant()) {
                case Tenant.Unscoped unscoped -> Optional.of("principal " + principal.id() + " has no tenant");
                case Tenant.Scoped(String tenant) -> resolver.tenantOf(type, id)
                        .map(owner -> owner.equals(tenant)
                                ? Optional.<String>empty()
                                : Optional.of(type + " '" + id + "' belongs to tenant " + owner
                                        + ", principal " + principal.id() + " is in tenant " + tenant))
                        .orElseGet(() -> Optional.of(type + " '" + id + "' is unknown"));
            };
            case ArgumentRule.OwnedByPrincipal(String type) -> resolver.ownerOf(type, id)
                    .map(owner -> owner.equals(principal.id())
                            ? Optional.<String>empty()
                            : Optional.of(type + " '" + id + "' belongs to " + owner + ", not " + principal.id()))
                    .orElseGet(() -> Optional.of(type + " '" + id + "' is unknown"));
            default -> throw new IllegalStateException("not an ownership rule: " + rule);
        };
    }

    /** A text or number ID, or a list of them; anything else, or a blank ID, is not an ID. */
    private static Optional<List<String>> ids(ArgValue value) {
        return switch (value) {
            case TextValue(String text) -> text.isBlank() ? Optional.empty() : Optional.of(List.of(text));
            // A number with no reasonable plain text, such as 1e999999999, is not an ID.
            case NumberValue(var number) -> Decimals.plainText(number).map(List::of);
            case ListValue(List<ArgValue> elements) -> {
                var all = new ArrayList<String>();
                for (ArgValue element : elements) {
                    if (element instanceof ListValue) {
                        yield Optional.empty();
                    }
                    Optional<List<String>> one = ids(element);
                    if (one.isEmpty()) {
                        yield Optional.empty();
                    }
                    all.addAll(one.get());
                }
                yield Optional.of(all);
            }
            default -> Optional.empty();
        };
    }

    private Verdict deny(String reason) {
        return new Verdict.Deny("argument '" + argument.name() + "': " + reason, Origin.ACCESS);
    }
}
