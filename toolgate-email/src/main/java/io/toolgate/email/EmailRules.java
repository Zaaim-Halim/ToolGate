package io.toolgate.email;

import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.api.policy.ArgumentRules;
import java.util.Arrays;

/**
 * Email rules for {@code ToolGatePolicy.AgentBuilder.rule}:
 *
 * <pre>{@code
 * .rule("sendEmail.to", emailDomains("mycompany.com", "*.mycompany.com"))
 * }</pre>
 *
 * <p>The argument is a string of comma-separated recipients, or a list of such strings. Every
 * recipient must be at an allowed domain, so {@code "attacker@evil.example, ops@mycompany.com"} is
 * denied. Only plain {@code local@domain} addresses are accepted, optionally as
 * {@code Maria Lopez <maria@mycompany.com>} with a display name of letters, digits, spaces and
 * {@code . - '}; every other form RFC 5322 allows is denied, because mail libraries read them
 * differently.
 */
public final class EmailRules {

    private EmailRules() {}

    /**
     * Allows recipients at these domains only.
     *
     * @param patterns {@code mycompany.com} for that domain exactly, {@code *.mycompany.com} for its
     *                 subdomains; case is ignored, and Unicode domains are written in their {@code xn--} form
     * @return the rule
     * @throws IllegalArgumentException if no pattern is given, or one is not a domain name
     */
    public static ArgumentRule.Validated emailDomains(String... patterns) {
        return ArgumentRules.validated(new EmailDomains(Arrays.stream(patterns).map(DomainPattern::parse).toList()));
    }
}
