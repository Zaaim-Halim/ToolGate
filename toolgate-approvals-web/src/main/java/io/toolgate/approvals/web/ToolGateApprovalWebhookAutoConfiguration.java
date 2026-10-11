package io.toolgate.approvals.web;

import io.toolgate.springai.ToolGateAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Approval webhooks, when the application has {@code toolgate-webhook} and configures at least one under
 * {@code toolgate.approvals.notify.webhooks}. Independent of the console, like approval mail.
 */
@AutoConfiguration(before = ToolGateAutoConfiguration.class)
@ConditionalOnClass(name = "io.toolgate.webhook.ApprovalWebhook")
@ConditionalOnProperty(prefix = "toolgate.approvals.notify", name = "webhooks[0].url")
@EnableConfigurationProperties(NotifyProperties.class)
public class ToolGateApprovalWebhookAutoConfiguration {

    /** Creates the configuration; Spring Boot does this. */
    public ToolGateApprovalWebhookAutoConfiguration() {}

    /**
     * The configured webhooks, as one approval listener the gate picks up.
     *
     * @param properties the {@code toolgate.approvals.notify.*} settings
     * @return the webhooks, closed when the application stops
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ToolGateWebhooks toolGateWebhooks(NotifyProperties properties) {
        return new ToolGateWebhooks(properties);
    }
}
