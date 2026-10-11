package io.toolgate.approvals.web;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code toolgate.approvals.notify.*}.
 *
 * @param consoleUrl the console's public address, such as {@code https://refunds.example.com/toolgate/approvals/},
 *                   so notifications link to the call; without it they carry the approval ID only
 * @param email      email notifications, under {@code toolgate.approvals.notify.email.*}
 */
@ConfigurationProperties("toolgate.approvals.notify")
public record NotifyProperties(String consoleUrl, @DefaultValue Email email) {

    /**
     * Email to reviewers through the application's {@code MailSender}, such as Spring Boot's with
     * {@code spring-boot-starter-mail}.
     *
     * @param enabled whether to send; off by default
     * @param from    the sender address
     * @param to      who hears about every agent's calls, and about super admin bursts and unknown outcomes
     * @param agents  who hears about one agent's calls, by agent ID, besides {@code to}
     */
    public record Email(boolean enabled, String from, @DefaultValue List<String> to,
                        @DefaultValue Map<String, List<String>> agents) {}
}
