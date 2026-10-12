package io.toolgate.api;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * A policy drafted from recorded tool calls, as Java source to review, by {@link PolicySuggestion}.
 *
 * @param sessions   sessions in the history
 * @param calls      tool calls in the history
 * @param counted    calls the draft is drawn from
 * @param leftOut    calls left out because the recorded policy refused them, by the rule that did
 * @param forReview  tools and values seen too rarely to suggest, listed in a comment for review
 * @param from       the earliest call, or null for an empty history
 * @param to         the latest call, or null for an empty history
 * @param javaSource a class implementing {@code Supplier<ToolGatePolicy>}, in monitor mode
 */
public record SuggestedPolicy(int sessions, int calls, int counted, Map<String, Integer> leftOut, int forReview,
                              Instant from, Instant to, String javaSource) {

    /** Copies the map. */
    public SuggestedPolicy {
        leftOut = Map.copyOf(leftOut);
    }

    /**
     * The draft's figures as text.
     *
     * <pre>
     * Drafted from 1,284 of 1,298 tool calls in 212 sessions
     * Left out, refused by the recorded policy: 14 (sendEmail.to: 9, budget.toolCalls: 5)
     * Seen too rarely to suggest, listed for review: 3
     * </pre>
     *
     * @return the text
     */
    public String summary() {
        var text = new StringBuilder(String.format(Locale.ROOT, "Drafted from %,d of %,d tool calls in %,d sessions%n",
                counted, calls, sessions));
        int refused = leftOut.values().stream().mapToInt(Integer::intValue).sum();
        if (refused > 0) {
            text.append(String.format(Locale.ROOT, "Left out, refused by the recorded policy: %,d (%s)%n", refused,
                    String.join(", ", leftOut.entrySet().stream().sorted(Map.Entry.comparingByKey())
                            .map(entry -> entry.getKey() + ": " + entry.getValue()).toList())));
        }
        if (forReview > 0) {
            text.append(String.format(Locale.ROOT, "Seen too rarely to suggest, listed for review: %,d%n", forReview));
        }
        return text.toString();
    }
}
