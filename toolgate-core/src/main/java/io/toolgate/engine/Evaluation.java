package io.toolgate.engine;

import io.toolgate.api.Decision;
import java.util.Objects;

/**
 * The outcome of evaluating one call.
 *
 * @param effective the decision to apply, after per-rule modes
 * @param computed  the decision a fully enforced policy would have made, for the audit trail
 */
public record Evaluation(Decision effective, Decision computed) {

    public Evaluation {
        Objects.requireNonNull(effective, "effective");
        Objects.requireNonNull(computed, "computed");
    }
}
