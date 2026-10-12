package io.toolgate.rules;

import io.toolgate.api.Effect;
import io.toolgate.api.Label;
import io.toolgate.api.policy.ToolPolicy;
import io.toolgate.engine.EvaluationContext;
import io.toolgate.spi.SessionState;

/**
 * When the trifecta and irreversible rules object to a call, bindings aside: one definition for the rules
 * themselves and for the engine, which names the binding that let such a call through.
 */
public final class TaintConditions {

    private TaintConditions() {}

    /**
     * The trifecta: the session holds private data and untrusted content, and the tool is an external sink.
     *
     * @param context the call and its session
     * @return {@code true} if the trifecta rule objects, unless a binding holds
     */
    public static boolean trifecta(EvaluationContext context) {
        SessionState session = context.session();
        return session.holds(Label.PRIVATE_DATA) && session.holds(Label.UNTRUSTED_CONTENT)
                && context.policy().tool(context.call().tool()).has(Label.EXTERNAL_SINK);
    }

    /**
     * The session holds untrusted content, and the tool is irreversible.
     *
     * @param context the call and its session
     * @return {@code true} if the irreversible rule objects, unless a binding holds
     */
    public static boolean irreversible(EvaluationContext context) {
        ToolPolicy tool = context.policy().tool(context.call().tool());
        return context.session().holds(Label.UNTRUSTED_CONTENT) && tool.effect() == Effect.IRREVERSIBLE;
    }
}
