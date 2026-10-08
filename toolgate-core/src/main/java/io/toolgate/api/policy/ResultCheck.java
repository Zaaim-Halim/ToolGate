package io.toolgate.api.policy;

/**
 * What a result rule checks about each value its path reaches in a tool's output.
 *
 * <p>{@link ResultRules#matchesTenant()} and {@link ResultRules#matchesPrincipal()} compare the value
 * itself, with no lookup. {@link ArgumentRules#sameTenant} and {@link ArgumentRules#ownedByPrincipal}
 * treat it as a record ID and ask the application's {@code OwnershipResolver}.
 */
public sealed interface ResultCheck permits ResultCheck.MatchesTenant, ResultCheck.MatchesPrincipal,
        ArgumentRule.SameTenant, ArgumentRule.OwnedByPrincipal {

    /** The value must equal the principal's tenant ID. */
    record MatchesTenant() implements ResultCheck {}

    /** The value must equal the principal's ID. */
    record MatchesPrincipal() implements ResultCheck {}
}
