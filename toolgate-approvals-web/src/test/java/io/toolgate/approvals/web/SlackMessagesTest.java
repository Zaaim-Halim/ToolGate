package io.toolgate.approvals.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.ApprovalAccess;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.Principal;
import io.toolgate.api.ReviewArgument;
import io.toolgate.api.ReviewItem;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a Slack approval message carries, and when it may not carry an Approve button. */
class SlackMessagesTest {

    @Test
    void aViewThatFitsGetsAnApproveButton() {
        assertThat(SlackMessages.review(item(List.of(shown("order", "7731"))), null)).contains(SlackMessages.APPROVE);
    }

    /** Slack would cut a long section: the reviewer would approve what they did not see in full. */
    @Test
    void aValueTooLongForSlackGetsNoApproveButton() {
        String message = SlackMessages.review(item(List.of(shown("note", "x".repeat(SlackMessages.SECTION_LIMIT)))),
                "https://console.example/?id=1");

        assertThat(message).doesNotContain(SlackMessages.APPROVE).contains("Too long to review here")
                .contains("Review in the console");
    }

    @Test
    void moreArgumentsThanSlackShowsGetNoApproveButton() {
        var arguments = new ArrayList<ReviewArgument>();
        for (int i = 0; i < SlackMessages.MAX_BLOCKS; i++) {
            arguments.add(shown("a" + i, "v"));
        }

        assertThat(SlackMessages.review(item(arguments), null)).doesNotContain(SlackMessages.APPROVE);
    }

    @Test
    void aViewerWhoMayNotDecideGetsNoApproveButton() {
        ReviewItem item = item(List.of(shown("order", "7731")));
        ReviewItem readOnly = new ReviewItem(item.approvalId(), item.agentId(), item.tool(), item.title(), item.requester(),
                item.status(), item.expiresAt(), item.deferralRule(), item.deferralReason(), item.arguments(),
                item.provenance(), item.decisions(), item.access(), false, item.digest());

        assertThat(SlackMessages.review(readOnly, null)).doesNotContain(SlackMessages.APPROVE);
    }

    /** The example from Slack's "Verifying requests from Slack". */
    @Test
    void signsAsSlackDocumentsIt() {
        String body = "token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow&channel_id=G8PSS9T3V"
                + "&channel_name=foobar&user_id=U2CERLKJA&user_name=roadrunner&command=%2Fwebhook-collect&text="
                + "&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands%2FT1DC2JH3J%2F397700885554%2F96rGlfmibIGlgcZRskXaIFfN"
                + "&trigger_id=398738663015.47445629121.803a0bc887a14d10d2c447fce8b6703c";

        assertThat(SlackSignature.sign("8f742231b10e8888abcd99yyyzzz85a5", "1531420618", body.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v0=a2114d57b48eac39b9ad189dd8316235a7b4a8d21a10bd27519666489c69b503");
    }

    private static ReviewArgument shown(String name, String value) {
        return new ReviewArgument(name, value, ReviewArgument.Visibility.SHOWN, List.of());
    }

    private static ReviewItem item(List<ReviewArgument> arguments) {
        return new ReviewItem("call-1", "refund-agent", ToolId.local("issueRefund"), "Refund an order",
                Principal.service("refund-job", Tenant.of("acme")), ApprovalStatus.PENDING, Instant.EPOCH,
                "taint.irreversible", "session holds untrusted content", arguments, List.of(), List.of(),
                ApprovalAccess.REVIEWER, true, "sha256:" + "a".repeat(64));
    }
}
