package io.toolgate.spi;

import io.toolgate.api.ArgValue;
import java.util.Optional;

/**
 * Checks one argument value against one rule, for rules that live outside core, such as
 * {@code emailDomains} in {@code toolgate-email}. Wrap one with
 * {@link io.toolgate.api.policy.ArgumentRules#validated} to use it in a policy.
 *
 * <p>A validator is trusted code on the decision path: it must be thread-safe, quick and free of
 * side effects, and it should normalize before it compares. When it throws, the call is denied.
 */
public interface ArgumentValidator {

    /**
     * The rule's name, such as {@code emailDomains}. An argument takes each kind at most once.
     *
     * @return a name of letters, digits, {@code .} and {@code -}, starting with a letter
     */
    String kind();

    /**
     * The rule's configuration as stable text, so the policy hash changes when it does; for example
     * the sorted domain list.
     *
     * @return the configuration, the same on every call and every instance
     */
    String fingerprint();

    /**
     * Why a value must not be used.
     *
     * @param value the argument's value, of any shape; a validator denies shapes it does not expect
     * @return the reason, or empty if the value is allowed
     */
    Optional<String> problem(ArgValue value);
}
