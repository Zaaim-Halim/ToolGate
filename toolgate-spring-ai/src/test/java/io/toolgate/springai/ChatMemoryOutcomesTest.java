package io.toolgate.springai;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.ApprovalAccess;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ToolId;
import io.toolgate.spi.AuditEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;

/** What the agent is told through chat memory when its deferred calls are decided or finish. */
class ChatMemoryOutcomesTest {

    private final MessageWindowChatMemory memory = MessageWindowChatMemory.builder().build();
    private final ChatMemoryOutcomes outcomes = new ChatMemoryOutcomes(memory);

    @Test
    void anApprovedCallThatRanIsReportedWithoutItsOutput() {
        outcomes.finished(event(ApprovalStatus.EXECUTED, "toolgate", null, "the tool ran"));

        assertThat(memory.get("chat-1")).singleElement().extracting(Message::getText)
                .isEqualTo("Update from ToolGate: the action local/issueRefund (approval chat-1:c2) was approved and has run.");
    }

    @Test
    void failuresUnknownOutcomesAndRejectionsAreReportedAndReleasesWaitForTheRun() {
        outcomes.finished(event(ApprovalStatus.FAILED, "node-1", null, "the tool failed"));
        outcomes.finished(event(ApprovalStatus.UNKNOWN, "toolgate", null, "lease ran out"));
        outcomes.decided(event(ApprovalStatus.REJECTED, "lena", ApprovalAccess.REVIEWER, "duplicate"));
        outcomes.decided(event(ApprovalStatus.RELEASED, "lena", ApprovalAccess.REVIEWER, "approved"));

        assertThat(memory.get("chat-1")).extracting(Message::getText).containsExactly(
                "Update from ToolGate: the action local/issueRefund (approval chat-1:c2) was approved, but failed when it ran.",
                "Update from ToolGate: the action local/issueRefund (approval chat-1:c2) was approved, but whether it ran is"
                        + " not known yet; a person is checking.",
                "Update from ToolGate: the action local/issueRefund (approval chat-1:c2) was rejected by a reviewer and did"
                        + " not run.");
    }

    private static AuditEvent.ApprovalDecided event(ApprovalStatus status, String by, ApprovalAccess access, String reason) {
        return new AuditEvent.ApprovalDecided(Instant.parse("2026-10-11T10:00:00Z"), "chat-1:c2", "chat-1",
                "refund-chat", ToolId.local("issueRefund"), status, by, access, reason);
    }
}
