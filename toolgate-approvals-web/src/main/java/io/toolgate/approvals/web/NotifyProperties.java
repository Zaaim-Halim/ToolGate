package io.toolgate.approvals.web;

import java.net.URI;
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
 * @param webhooks   webhooks to post to, under {@code toolgate.approvals.notify.webhooks[n].*}; they need
 *                   {@code toolgate-webhook} on the classpath
 */
@ConfigurationProperties("toolgate.approvals.notify")
public record NotifyProperties(String consoleUrl, @DefaultValue Email email, @DefaultValue List<Webhook> webhooks) {

    /**
     * One webhook. Format and events are names, so these settings bind without {@code toolgate-webhook}.
     *
     * @param url    where to post: HTTPS
     * @param format {@code signed-json} (the default), {@code slack} or {@code teams}
     * @param secret the shared secret for {@code signed-json}, at least 32 random characters; keep it out of
     *               source code, such as in an environment variable
     * @param events which events to post, such as {@code deferred, expiring}; every one by default:
     *               {@code deferred}, {@code expiring}, {@code decided}, {@code recorded}, {@code finished},
     *               {@code super-admin-burst}
     */
    public record Webhook(URI url, @DefaultValue("signed-json") String format, String secret,
                          @DefaultValue List<String> events) {}

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
