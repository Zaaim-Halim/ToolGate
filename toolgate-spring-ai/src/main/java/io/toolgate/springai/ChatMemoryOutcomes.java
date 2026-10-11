package io.toolgate.springai;

import io.toolgate.spi.ApprovalListener;
import io.toolgate.spi.AuditEvent;
import java.util.Objects;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * Tells the agent what became of the calls it asked for, through the conversation's chat memory: when a
 * deferred call is rejected, cancelled, expires or is refused, and when an approved one ran, failed, or
 * has an unknown outcome, a message ToolGate writes itself is added to the conversation, so the agent
 * knows on the user's next turn. The tool's own output is never added: it went through
 * {@code recordResult} when the tool ran, and could carry anything.
 *
 * <p>Turned on with {@code toolgate.approvals.outcome.chat-memory=true}. The session must be the
 * conversation ID, as it is when ToolGate takes it from Spring AI's chat memory.
 */
public final class ChatMemoryOutcomes implements ApprovalListener {

    private final ChatMemory memory;

    ChatMemoryOutcomes(ChatMemory memory) {
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    @Override
    public void decided(AuditEvent.ApprovalDecided event) {
        String what = switch (event.status()) {
            case REJECTED -> "was rejected by a reviewer and did not run";
            case CANCELLED -> "was cancelled and did not run";
            case EXPIRED -> "expired before anyone decided, and did not run";
            case REFUSED -> "was approved, but no longer passes the policy, so it did not run";
            // Released calls are reported when they finish; the others never reach decided.
            default -> null;
        };
        if (what != null) {
            tell(event, what);
        }
    }

    @Override
    public void finished(AuditEvent.ApprovalDecided event) {
        tell(event, switch (event.status()) {
            case EXECUTED -> "was approved and has run";
            case FAILED -> "was approved, but failed when it ran";
            default -> "was approved, but whether it ran is not known yet; a person is checking";
        });
    }

    private void tell(AuditEvent.ApprovalDecided event, String what) {
        memory.add(event.sessionId(), new AssistantMessage("Update from ToolGate: the action " + event.tool()
                + " (approval " + event.approvalId() + ") " + what + "."));
    }
}
