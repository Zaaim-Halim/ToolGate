package io.toolgate.api.policy;

import static io.toolgate.support.Checks.requireNonBlank;

import java.util.Map;
import java.util.Set;

/**
 * What a reviewer sees of one tool's deferred calls. Display only: it changes no decision, so it is not
 * part of the policy hash.
 *
 * @param title  what the screen calls the action, such as "Refund an order"
 * @param show   arguments shown in full
 * @param masks  arguments shown masked, with their masks
 * @param hidden arguments not shown at all
 */
public record ReviewView(String title, Set<String> show, Map<String, Mask> masks, Set<String> hidden) {

    /** Requires a title, copies the sets, and refuses an argument named twice. */
    public ReviewView {
        requireNonBlank(title, "title");
        show = Set.copyOf(show);
        masks = Map.copyOf(masks);
        hidden = Set.copyOf(hidden);
        for (String argument : show) {
            if (masks.containsKey(argument) || hidden.contains(argument)) {
                throw new InvalidPolicyException("review argument '" + argument + "' is set more than once");
            }
        }
        for (String argument : masks.keySet()) {
            if (hidden.contains(argument)) {
                throw new InvalidPolicyException("review argument '" + argument + "' is set more than once");
            }
        }
    }
}
