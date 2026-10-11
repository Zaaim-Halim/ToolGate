package io.toolgate.springai;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.client.ChatClientResponse;

/**
 * The tool calls of one chat request that ToolGate deferred, read from its response:
 *
 * <pre>{@code
 * ChatClientResponse response = chatClient.prompt().user(question).call().chatClientResponse();
 * for (DeferredAction action : ToolGateDeferrals.of(response)) {
 *     // show "Confirm?" with action.approvalId()
 * }
 * }</pre>
 *
 * The same calls are also in {@link ToolGateApprovals#pending(io.toolgate.api.Principal)}; this saves a
 * chat screen from looking for them.
 */
public final class ToolGateDeferrals {

    /** Where ToolGate's advisor puts the request's deferrals, in the tool context and the response context. */
    static final String KEY = "toolgate.deferrals";

    private final List<DeferredAction> actions = new CopyOnWriteArrayList<>();

    ToolGateDeferrals() {}

    /**
     * The deferred calls of the request that produced a response.
     *
     * @param response a response from a {@code ChatClient} that ToolGate gates
     * @return the deferred calls, in the order the model made them; empty if none
     */
    public static List<DeferredAction> of(ChatClientResponse response) {
        return response.context().get(KEY) instanceof ToolGateDeferrals deferrals ? deferrals.actions() : List.of();
    }

    /** @return the deferred calls so far */
    List<DeferredAction> actions() {
        return List.copyOf(actions);
    }

    void add(DeferredAction action) {
        actions.add(action);
    }
}
