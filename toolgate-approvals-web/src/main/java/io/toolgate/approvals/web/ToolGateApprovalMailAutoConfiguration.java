package io.toolgate.approvals.web;

import io.toolgate.springai.ToolGateAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.MailSender;

/**
 * Approval mail, when the application turns it on with {@code toolgate.approvals.notify.email.enabled=true}:
 * sent through the application's {@link MailSender}, such as Spring Boot's with {@code spring-boot-starter-mail}.
 * Independent of the console, so a headless application can mail reviewers who decide elsewhere.
 */
@AutoConfiguration(before = ToolGateAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.mail.MailSender")
@ConditionalOnProperty(prefix = "toolgate.approvals.notify.email", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(NotifyProperties.class)
public class ToolGateApprovalMailAutoConfiguration {

    /** Creates the configuration; Spring Boot does this. */
    public ToolGateApprovalMailAutoConfiguration() {}

    /**
     * The mailer, an approval listener the gate picks up.
     *
     * @param sender     the application's mail sender; required
     * @param properties the {@code toolgate.approvals.notify.*} settings
     * @return the mailer, closed when the application stops
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ApprovalMailer toolGateApprovalMailer(ObjectProvider<MailSender> sender, NotifyProperties properties) {
        MailSender available = sender.getIfAvailable();
        if (available == null) {
            throw new IllegalStateException("toolgate.approvals.notify.email.enabled=true needs a MailSender bean,"
                    + " such as Spring Boot's with spring-boot-starter-mail and spring.mail.host");
        }
        return new ApprovalMailer(available, properties);
    }
}
