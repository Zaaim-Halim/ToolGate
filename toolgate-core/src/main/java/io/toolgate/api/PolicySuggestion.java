package io.toolgate.api;

import io.toolgate.engine.Suggester;
import io.toolgate.spi.SessionEvent;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Drafts a policy from recorded tool calls, such as a week of monitor mode: the tools each agent used,
 * and for each argument the hosts and schemes of its URLs, the domains of its email addresses, the
 * roots of its paths, or the bounds of its numbers. Also a tool-call budget per agent, from the busiest
 * session.
 *
 * <pre>{@code
 * try (Stream<SessionEvent> history = jdbcSessionStore.events(lastWeek, now)) {
 *     SuggestedPolicy draft = PolicySuggestion.builder().build().suggest(history);
 *     Files.writeString(Path.of("SuggestedPolicy.java"), draft.javaSource());
 * }
 * }</pre>
 *
 * <p>History also holds what an injected model asked for, so the draft is a starting point to review,
 * never a policy to deploy as is. Two things keep an attack out of it. A call the recorded policy
 * refused, other than for not being listed yet, is left out. And a tool, host, email domain or path
 * root seen in fewer sessions than {@link Builder#minSessions} is not allowed but listed in a comment
 * for review. A number's limit is its 95th percentile, or lower: no larger than a value reached in that
 * many sessions, so one session's outlier never sets it; larger numbers are deferred for review. Only these
 * generalizations reach the draft, never an argument's value. Labels and effects cannot be seen in
 * history, so the draft leaves them to you, and it starts in monitor mode.
 */
public final class PolicySuggestion {

    private static final Pattern CLASS_NAME =
            Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*");

    private final int minSessions;
    private final String className;

    private PolicySuggestion(Builder builder) {
        this.minSessions = builder.minSessions;
        this.className = builder.className;
    }

    /**
     * Starts a suggestion.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Drafts a policy from history.
     *
     * @param history recorded session events, such as {@code JdbcSessionStore.events(from, to)}
     * @return the draft
     */
    public SuggestedPolicy suggest(Stream<SessionEvent> history) {
        return new Suggester(minSessions, className).suggest(Objects.requireNonNull(history, "history"));
    }

    /** Settings for a suggestion. */
    public static final class Builder {

        private int minSessions = 3;
        private String className = "SuggestedPolicy";

        private Builder() {}

        /**
         * How many sessions a tool, host, email domain or path root must appear in to be suggested;
         * 3 by default. Rarer ones are listed for review.
         *
         * @param sessions at least 1
         * @return this builder
         */
        public Builder minSessions(int sessions) {
            if (sessions < 1) {
                throw new IllegalArgumentException("minSessions must be at least 1, was " + sessions);
            }
            this.minSessions = sessions;
            return this;
        }

        /**
         * The generated class's name, with its package if any; {@code SuggestedPolicy} by default.
         *
         * @param name such as {@code com.acme.SuggestedPolicy}
         * @return this builder
         */
        public Builder className(String name) {
            if (name == null || !CLASS_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("not a Java class name: " + name);
            }
            this.className = name;
            return this;
        }

        /**
         * Builds the suggestion.
         *
         * @return the suggestion
         */
        public PolicySuggestion build() {
            return new PolicySuggestion(this);
        }
    }
}
