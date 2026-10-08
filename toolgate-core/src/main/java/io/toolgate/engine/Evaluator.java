package io.toolgate.engine;

/**
 * One family of rules. Evaluators are stateless: anything they need is in the context.
 *
 * <p>Internal for now. Once the shape has settled through the taint and ownership rules, it is a
 * candidate for the {@code spi} package so teams can add their own.
 */
public interface Evaluator {

    /** The rule ID used in decisions, audit events and per-rule modes. */
    String id();

    /** When this evaluator runs. */
    Stage stage();

    /** Judges one call. May throw; the engine turns any exception into a deny. */
    Verdict evaluate(EvaluationContext context);
}
