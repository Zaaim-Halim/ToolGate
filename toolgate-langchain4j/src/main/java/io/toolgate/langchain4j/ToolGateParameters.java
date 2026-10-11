package io.toolgate.langchain4j;

import dev.langchain4j.invocation.InvocationParameters;
import io.toolgate.api.Principal;
import java.util.Map;
import java.util.Objects;

/**
 * Who a call is for and which session it belongs to, passed with every AI service call as
 * LangChain4j invocation parameters, never through thread-locals: LangChain4j runs tools on the
 * caller's thread, on virtual threads and on the streaming pool.
 *
 * <pre>{@code
 * String chat(@UserMessage String question, InvocationParameters parameters);
 *
 * assistant.chat(question, ToolGateParameters.of(maria, "chat-42"));
 * }</pre>
 *
 * <p>Without a session, the chat memory ID is the session. Without a principal, the identity the
 * policy declares for the agent is used; with neither, the run ends.
 */
public final class ToolGateParameters {

    /** The principal the agent acts for, a {@link Principal}. */
    public static final String PRINCIPAL = "toolgate.principal";
    /** The session the call belongs to, a non-blank string. */
    public static final String SESSION = "toolgate.session";
    /** Set on a released call's parameters: the approval's idempotency key, for tools that may run twice. */
    public static final String IDEMPOTENCY_KEY = "toolgate.idempotencyKey";

    private ToolGateParameters() {}

    /**
     * The parameters for one AI service call.
     *
     * @param principal who the agent acts for
     * @param session   the session, such as a conversation or job ID
     * @return the invocation parameters
     */
    public static InvocationParameters of(Principal principal, String session) {
        Objects.requireNonNull(principal, "principal");
        if (session == null || session.isBlank()) {
            throw new IllegalArgumentException("session must not be blank");
        }
        return InvocationParameters.from(Map.of(PRINCIPAL, principal, SESSION, session));
    }

    static boolean isToolGateKey(String key) {
        return key.startsWith("toolgate.");
    }
}
