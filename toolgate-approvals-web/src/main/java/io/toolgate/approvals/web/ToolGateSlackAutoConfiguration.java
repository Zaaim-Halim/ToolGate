package io.toolgate.approvals.web;

import io.toolgate.api.ToolGate;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.springai.ToolGateAutoConfiguration;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Approval messages in Slack, on with {@code toolgate.approvals.slack.channels[0].webhook-url}. With a
 * {@link ToolGateSlackReviewers} bean and the app's signing secret, app ID and workspace ID, messages of
 * channels that show arguments carry an Approve button, and {@value SlackActionsController#PATH} takes the
 * clicks, on Spring MVC.
 */
@AutoConfiguration(after = ToolGateAutoConfiguration.class)
@ConditionalOnProperty(prefix = "toolgate.approvals.slack", name = "channels[0].webhook-url")
@EnableConfigurationProperties({SlackProperties.class, NotifyProperties.class})
public class ToolGateSlackAutoConfiguration {

    /**
     * Posts approval messages.
     *
     * @return the notifier, an approval listener the gate calls; closed when the application stops
     */
    @Bean(destroyMethod = "close")
    SlackApprovalNotifier toolGateSlackNotifier(SlackProperties slack, NotifyProperties notify, ObjectProvider<ToolGate> gate,
                                               ObjectProvider<ToolGatePolicy> policy, ObjectProvider<ToolGateSlackReviewers> reviewers,
                                               ObjectProvider<SlackClient> client) {
        requireSafeChannels(slack, notify, policy.getObject());
        boolean approveInSlack = reviewers.getIfAvailable() != null && configured(slack);
        return new SlackApprovalNotifier(slack.channels(), gate::getObject, approveInSlack, notify.consoleUrl(),
                client.getIfAvailable(SlackClient::http));
    }

    /** Takes clicks on Approve buttons, when Slack users can be mapped to reviewers. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnBean(ToolGateSlackReviewers.class)
    static class Actions {

        @Bean
        SlackActionsController toolGateSlackActions(SlackProperties slack, ObjectProvider<ToolGate> gate,
                                                    ObjectProvider<ToolGatePolicy> policy, ToolGateSlackReviewers reviewers,
                                                    ObjectProvider<ToolGateRequesterDirectory> requesters,
                                                    ObjectProvider<SlackClient> client, ObjectProvider<Clock> clock) {
            if (!configured(slack)) {
                throw new IllegalStateException("approving in Slack needs toolgate.approvals.slack.signing-secret,"
                        + " app-id and team-id");
            }
            return new SlackActionsController(slack, gate::getObject, policy::getObject, reviewers,
                    requesters.getIfAvailable(() -> asDeferred -> asDeferred), client.getIfAvailable(SlackClient::http),
                    clock.getIfAvailable(Clock::systemUTC));
        }

        /**
         * Slack is not signed in to the application: the endpoint proves each request by its signature, so
         * Spring Security leaves this one path, POST only, to it. A customizer, not a filter chain of its own,
         * so the application's default chain stays as it is.
         */
        @Bean
        @ConditionalOnClass(name = "org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer")
        WebSecurityCustomizer toolGateSlackActionsUnauthenticated() {
            return web -> web.ignoring().requestMatchers(
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, SlackActionsController.PATH));
        }
    }

    private static boolean configured(SlackProperties slack) {
        return notBlank(slack.signingSecret()) && notBlank(slack.appId()) && notBlank(slack.teamId());
    }

    private static boolean notBlank(String text) {
        return text != null && !text.isBlank();
    }

    /**
     * A channel shows what one reviewer may see, so its roles must not be super admin or auditor roles, which
     * see every tenant; and a channel must not also be a plain Slack webhook, or each call is posted twice.
     */
    static void requireSafeChannels(SlackProperties slack, NotifyProperties notify, ToolGatePolicy policy) {
        Set<String> wide = new HashSet<>(policy.superAdmins());
        wide.addAll(policy.auditors());
        for (int index = 0; index < slack.channels().size(); index++) {
            SlackProperties.Channel channel = slack.channels().get(index);
            String name = "toolgate.approvals.slack.channels[" + index + "]";
            if (channel.webhookUrl() == null || !"https".equals(channel.webhookUrl().getScheme())) {
                throw new IllegalStateException(name + ".webhook-url must be an HTTPS URL");
            }
            if (channel.roles().stream().anyMatch(wide::contains)) {
                throw new IllegalStateException(name + ".roles must be reviewer roles, not super admin or auditor roles");
            }
            boolean twice = notify.webhooks().stream().anyMatch(webhook -> channel.webhookUrl().equals(webhook.url()));
            if (twice) {
                throw new IllegalStateException(name + ".webhook-url is also a toolgate.approvals.notify webhook;"
                        + " remove one, or each call is posted twice");
            }
        }
    }
}
