package io.toolgate.approvals.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.toolgate.api.policy.ToolGatePolicy;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A Slack channel's settings that would show too much, leak a secret or post twice stop the application. */
class SlackChannelsTest {

    private static final ToolGatePolicy POLICY = ToolGatePolicy.builder().superAdmins("toolgate-admin")
            .auditors("compliance").agent("refund-agent", a -> a.allow("issueRefund").reviewers("finance")).build();
    private static final URI HOOK = URI.create("https://hooks.slack.com/services/T0/B0/x");

    @Test
    void aReviewerChannelOverHttpsStarts() {
        assertThatCode(() -> check(channel(HOOK, "finance"), List.of())).doesNotThrowAnyException();
    }

    @Test
    void superAdminOrAuditorRolesAreRefused() {
        assertThatIllegalStateException().isThrownBy(() -> check(channel(HOOK, "toolgate-admin"), List.of()))
                .withMessageContaining("reviewer roles");
        assertThatIllegalStateException().isThrownBy(() -> check(channel(HOOK, "compliance"), List.of()));
    }

    @Test
    void aPlainHttpWebhookIsRefused() {
        assertThatIllegalStateException()
                .isThrownBy(() -> check(channel(URI.create("http://hooks.slack.com/services/T0/B0/x"), "finance"), List.of()))
                .withMessageContaining("HTTPS");
    }

    @Test
    void aChannelThatIsAlsoANotifyWebhookIsRefused() {
        assertThatIllegalStateException().isThrownBy(() -> check(channel(HOOK, "finance"),
                        List.of(new NotifyProperties.Webhook(HOOK, "slack", null, List.of()))))
                .withMessageContaining("posted twice");
    }

    private static SlackProperties.Channel channel(URI url, String role) {
        return new SlackProperties.Channel(url, List.of(), "acme", List.of(role), true);
    }

    private static void check(SlackProperties.Channel channel, List<NotifyProperties.Webhook> webhooks) {
        ToolGateSlackAutoConfiguration.requireSafeChannels(
                new SlackProperties("secret", "A0", "T0", List.of("hooks.slack.com"), List.of(channel)),
                new NotifyProperties(null, new NotifyProperties.Email(false, null, List.of(), java.util.Map.of()), webhooks),
                POLICY);
    }
}
