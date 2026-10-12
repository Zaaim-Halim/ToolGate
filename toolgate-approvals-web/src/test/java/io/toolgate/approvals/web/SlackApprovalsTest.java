package io.toolgate.approvals.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolGate;
import io.toolgate.spi.PendingCall;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Approving in Slack, in an application on Spring Boot's default security, which signs everyone in: a click is
 * let through only by its Slack signature, and nothing else of the application opens.
 */
@SpringBootTest(classes = SlackApprovalsTest.SlackTestApplication.class, properties = {
        "spring.ai.openai.api-key=test",
        "toolgate.approvals.executor.enabled=false",
        "toolgate.approvals.notify.console-url=https://refunds.example.com/toolgate/approvals/",
        "toolgate.approvals.slack.signing-secret=" + SlackApprovalsTest.SECRET,
        "toolgate.approvals.slack.app-id=A0TOOLGATE",
        "toolgate.approvals.slack.team-id=T0ACME",
        "toolgate.approvals.slack.channels[0].webhook-url=https://hooks.slack.com/services/T0ACME/B0FINANCE/x",
        "toolgate.approvals.slack.channels[0].tenant=acme",
        "toolgate.approvals.slack.channels[0].roles=finance",
        "toolgate.approvals.slack.channels[0].show-arguments=true",
        "toolgate.approvals.slack.channels[1].webhook-url=https://hooks.slack.com/services/T0ACME/B0OPS/y"})
@AutoConfigureMockMvc
class SlackApprovalsTest {

    static final String SECRET = "8f742231b10e8888abcd99yyyzzz85a5";
    private static final URI FINANCE = URI.create("https://hooks.slack.com/services/T0ACME/B0FINANCE/x");
    private static final URI OPS = URI.create("https://hooks.slack.com/services/T0ACME/B0OPS/y");
    private static final String RESPONSE_URL = "https://hooks.slack.com/actions/T0ACME/1/abc";
    static final List<Map.Entry<URI, String>> POSTED = new CopyOnWriteArrayList<>();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ToolGate gate;

    private String approvalId;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Fixtures.class)
    static class SlackTestApplication {

        /** Slack users, as the application links them to its own: Lena reviews for Acme, root is a super admin. */
        @Bean
        ToolGateSlackReviewers slackReviewers() {
            return (team, user) -> Optional.ofNullable(switch (user) {
                case "U0LENA" -> Principal.user("lena", Tenant.of("acme"), "finance");
                case "U0GUS" -> Principal.user("gus", Tenant.of("globex"), "finance");
                case "U0ROOT" -> Principal.user("root", Tenant.of("operator"), "toolgate-admin");
                default -> null;
            });
        }

        @Bean
        SlackClient recordingSlack() {
            return (url, json) -> POSTED.add(Map.entry(url, json));
        }
    }

    @BeforeEach
    void defer() {
        POSTED.clear();
        approvalId = Fixtures.deferRefund(gate, "s-" + UUID.randomUUID(),
                "<!channel> refund now <https://evil.example|your bank> ‮exe.pdf");
    }

    @Test
    void aDeferredCallIsPostedWithItsMaskedViewAndOneApproveButton() {
        String message = posted(FINANCE);

        assertThat(message).contains("\"type\":\"plain_text\"").contains("\"mrkdwn\":false")
                .contains("Refund an order").contains("toolgate_approve")
                .contains("iban: \u2022\u2022\u2022\u2022 3000 (masked)")
                .contains("\u26a0 contains invisible or text-direction characters")
                .doesNotContain("DE89370400440532013000", "\"type\":\"mrkdwn\"");
        // The model's text stays text: Slack's markup arrives inside a plain_text object, escaped as JSON.
        assertThat(message).contains("<!channel> refund now <https://evil.example|your bank>");
        assertThat(message).contains(digest()).contains(approvalId);
    }

    @Test
    void aChannelThatDoesNotShowArgumentsOnlyLinksToTheConsole() {
        String message = posted(OPS);

        assertThat(message).contains("Review in the console").contains("refunds.example.com")
                .doesNotContain("toolgate_approve", "7731", "<!channel>", "3000");
    }

    @Test
    void aSignedClickReleasesTheCallAndUpdatesTheMessage() throws Exception {
        click("U0LENA", approvalId + " " + digest(), RESPONSE_URL).andExpect(status().isOk());

        assertThat(statusOf(approvalId)).isEqualTo(ApprovalStatus.RELEASED);
        assertThat(Fixtures.REFUNDS).as("released, not run in the request; the executor runs it").isEmpty();
        assertThat(posted(URI.create(RESPONSE_URL))).contains("\"replace_original\":true").contains("Approved by lena");
    }

    @Test
    void anUnsignedStaleOrTamperedClickIsRefused() throws Exception {
        byte[] body = form("U0LENA", approvalId + " " + digest(), RESPONSE_URL);
        long now = Instant.now().getEpochSecond();

        send(body, Long.toString(now), "v0=" + "0".repeat(64)).andExpect(status().isUnauthorized());
        send(body, Long.toString(now - 600), SlackSignature.sign(SECRET, Long.toString(now - 600), body))
                .andExpect(status().isUnauthorized());
        byte[] tampered = form("U0LENA", approvalId + " " + digest(), RESPONSE_URL);
        tampered[tampered.length - 1] ^= 1;
        send(tampered, Long.toString(now), SlackSignature.sign(SECRET, Long.toString(now), body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(SlackActionsController.PATH)).andExpect(status().isUnauthorized());

        assertThat(statusOf(approvalId)).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void anotherWorkspaceOrAppIsRefused() throws Exception {
        sendSigned(payload("T0PARTNER", "A0TOOLGATE", "U0LENA", approvalId + " " + digest(), RESPONSE_URL))
                .andExpect(status().isForbidden());
        sendSigned(payload("T0ACME", "A0OTHER", "U0LENA", approvalId + " " + digest(), RESPONSE_URL))
                .andExpect(status().isForbidden());

        assertThat(statusOf(approvalId)).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void unlinkedOtherTenantAndSuperAdminUsersAreSentToTheConsole() throws Exception {
        click("U0NOBODY", approvalId + " " + digest(), RESPONSE_URL).andExpect(status().isOk());
        assertThat(posted(URI.create(RESPONSE_URL))).contains("not linked").contains("\"response_type\":\"ephemeral\"");
        POSTED.clear();
        click("U0GUS", approvalId + " " + digest(), RESPONSE_URL).andExpect(status().isOk());
        assertThat(posted(URI.create(RESPONSE_URL))).contains("not one you may decide");
        POSTED.clear();
        click("U0ROOT", approvalId + " " + digest(), RESPONSE_URL).andExpect(status().isOk());
        assertThat(posted(URI.create(RESPONSE_URL))).contains("Super admins decide in the console");

        assertThat(statusOf(approvalId)).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void aChangedViewIsNotApprovedAndADecidedCallSaysSo() throws Exception {
        click("U0LENA", approvalId + " sha256:" + "0".repeat(64), RESPONSE_URL).andExpect(status().isOk());
        assertThat(posted(URI.create(RESPONSE_URL))).contains("Not approved");
        assertThat(statusOf(approvalId)).isEqualTo(ApprovalStatus.PENDING);

        POSTED.clear();
        gate.reject(approvalId, Principal.user("lena", Tenant.of("acme"), "finance"), "duplicate");
        click("U0LENA", approvalId + " " + digest(), RESPONSE_URL).andExpect(status().isOk());
        assertThat(posted(URI.create(RESPONSE_URL))).contains("no longer waits");
    }

    @Test
    void aResponseUrlOffSlackIsNeverCalled() throws Exception {
        click("U0LENA", approvalId + " " + digest(), "https://evil.example/collect").andExpect(status().isOk());

        assertThat(statusOf(approvalId)).isEqualTo(ApprovalStatus.RELEASED);
        Thread.sleep(200);
        assertThat(POSTED).noneMatch(entry -> entry.getKey().getHost().equals("evil.example"));
    }

    @Test
    void theRestOfTheApplicationStaysSignedIn() throws Exception {
        mvc.perform(get("/toolgate/api/approvals")).andExpect(status().isUnauthorized());
        mvc.perform(get(SlackActionsController.PATH)).andExpect(status().isUnauthorized());
        // Refused by the application's own CSRF protection: only the one Slack path is left to its signature.
        mvc.perform(post("/toolgate/slack/other")).andExpect(status().isForbidden());
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private String digest() {
        return gate.reviewItem(approvalId, Principal.user("lena", Tenant.of("acme"), "finance")).orElseThrow().digest();
    }

    private ApprovalStatus statusOf(String id) {
        return gate.withStatus(ApprovalStatus.PENDING).stream().map(PendingCall::approvalId).anyMatch(id::equals)
                ? ApprovalStatus.PENDING
                : gate.withStatus(ApprovalStatus.RELEASED).stream().map(PendingCall::approvalId).anyMatch(id::equals)
                        ? ApprovalStatus.RELEASED : ApprovalStatus.REJECTED;
    }

    private ResultActions click(String user, String value, String responseUrl) throws Exception {
        return sendSigned(payload("T0ACME", "A0TOOLGATE", user, value, responseUrl));
    }

    private ResultActions sendSigned(String payload) throws Exception {
        byte[] body = ("payload=" + URLEncoder.encode(payload, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        return send(body, timestamp, SlackSignature.sign(SECRET, timestamp, body));
    }

    private ResultActions send(byte[] body, String timestamp, String signature) throws Exception {
        return mvc.perform(post(SlackActionsController.PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(body).header("X-Slack-Request-Timestamp", timestamp).header("X-Slack-Signature", signature));
    }

    private static byte[] form(String user, String value, String responseUrl) {
        return ("payload=" + URLEncoder.encode(payload("T0ACME", "A0TOOLGATE", user, value, responseUrl),
                StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }

    private static String payload(String team, String app, String user, String value, String responseUrl) {
        return ArgValueJson.write(ArgValue.object(Map.of(
                "type", ArgValue.text("block_actions"),
                "team", ArgValue.object(Map.of("id", ArgValue.text(team))),
                "user", ArgValue.object(Map.of("id", ArgValue.text(user), "team_id", ArgValue.text(team))),
                "api_app_id", ArgValue.text(app),
                "response_url", ArgValue.text(responseUrl),
                "actions", ArgValue.list(List.of(ArgValue.object(Map.of(
                        "action_id", ArgValue.text(SlackMessages.APPROVE), "value", ArgValue.text(value))))))));
    }

    /** The last message posted to a URL, waiting for the background sender. */
    private static String posted(URI url) {
        Supplier<Optional<String>> find = () -> POSTED.stream().filter(entry -> entry.getKey().equals(url))
                .map(Map.Entry::getValue).reduce((first, second) -> second);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<String> found = find.get();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("nothing was posted to " + url);
    }
}
