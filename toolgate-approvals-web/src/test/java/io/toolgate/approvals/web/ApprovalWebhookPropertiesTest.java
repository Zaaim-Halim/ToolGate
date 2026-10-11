package io.toolgate.approvals.web;

import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.OnViolation.DEFER;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.toolgate.api.ArgValue;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.ToolCatalog;
import io.toolgate.springai.ToolGateAutoConfiguration;
import io.toolgate.webhook.ApprovalWebhook;
import io.toolgate.webhook.WebhookSignature;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Webhooks configured in application properties. */
class ApprovalWebhookPropertiesTest {

    private static final String SECRET = "a-random-secret-of-at-least-32-chars";

    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, String> signatures = new ConcurrentHashMap<>();
    private final HttpServer server = start();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolGateAutoConfiguration.class, ToolGateApprovalWebhookAutoConfiguration.class))
            .withBean(ToolGatePolicy.class, () -> ToolGatePolicy.builder()
                    .agent("refund-agent", a -> a.allow("issueRefund").reviewers("finance")
                            .rule("issueRefund.amount", atMost("1000"), atMost("200").otherwise(DEFER)))
                    .build())
            .withBean(ToolCatalog.Mutable.class, () -> ToolCatalog.inMemory().put(ToolDefinition.of(ToolId.local("issueRefund"),
                    "Refund an order", "{\"type\":\"object\",\"properties\":{\"order\":{},\"amount\":{}}}")))
            .withPropertyValues("toolgate.audit.log.enabled=false", "toolgate.approvals.executor.enabled=false");

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void everyConfiguredWebhookHearsOfAWaitingCall() {
        runner.withPropertyValues(
                        "toolgate.approvals.notify.console-url=https://refunds.example.com/toolgate/approvals/",
                        "toolgate.approvals.notify.webhooks[0].url=" + url("signed"),
                        "toolgate.approvals.notify.webhooks[0].secret=" + SECRET,
                        "toolgate.approvals.notify.webhooks[1].url=" + url("teams"),
                        "toolgate.approvals.notify.webhooks[1].format=teams",
                        "toolgate.approvals.notify.webhooks[1].events=deferred,expiring",
                        "toolgate.approvals.notify.webhooks[2].url=" + url("slack"),
                        "toolgate.approvals.notify.webhooks[2].format=slack",
                        "toolgate.approvals.notify.webhooks[2].events=super-admin-burst")
                .run(context -> {
                    ToolGate gate = context.getBean(ToolGate.class);
                    gate.check(new ToolCall("c1", Principal.service("refund-agent", Tenant.of("acme")), "refund-agent",
                            "refunds", ToolId.local("issueRefund"),
                            ObjectValue.of(Map.of("amount", ArgValue.number(480))), Instant.now()));
                    gate.close();
                    context.getBean(ToolGateWebhooks.class).close();

                    assertThat(context.getBean(ToolGateWebhooks.class).webhooks()).hasSize(3);
                    assertThat(bodies).containsOnlyKeys("/signed", "/teams");
                    assertThat(bodies.get("/signed")).contains("\"type\":\"approval.deferred\"",
                            "https://refunds.example.com/toolgate/approvals/?id=");
                    assertThat(WebhookSignature.verify(SECRET.getBytes(StandardCharsets.UTF_8), signatures.get("/signed"),
                            bodies.get("/signed"), Duration.ofMinutes(5))).isTrue();
                    assertThat(bodies.get("/teams")).contains("AdaptiveCard", "**refund-agent** asks to run");
                });
    }

    @Test
    void refusesToStartMisconfigured() {
        runner.withPropertyValues("toolgate.approvals.notify.webhooks[0].url=" + url("signed"))
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("webhooks[0].secret is required for signed-json"));
        runner.withPropertyValues("toolgate.approvals.notify.webhooks[0].url=" + url("x"),
                        "toolgate.approvals.notify.webhooks[0].format=pager")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("format must be signed-json, slack or teams"));
        runner.withPropertyValues("toolgate.approvals.notify.webhooks[0].url=" + url("x"),
                        "toolgate.approvals.notify.webhooks[0].format=slack", "toolgate.approvals.notify.webhooks[0].events=paged")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("names an unknown event"));
        // A Slack URL is a secret: the error names its host, never its path.
        runner.withPropertyValues("toolgate.approvals.notify.webhooks[0].url=http://hooks.slack.com/services/T0/B0/SECRETPATH",
                        "toolgate.approvals.notify.webhooks[0].format=slack")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("must use HTTPS").message().doesNotContain("SECRETPATH"));
    }

    @Test
    void offWithoutAWebhookOrWithoutTheModule() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ToolGateWebhooks.class));
        runner.withClassLoader(new FilteredClassLoader(ApprovalWebhook.class))
                .withPropertyValues("toolgate.approvals.notify.webhooks[0].url=" + url("signed"))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ToolGateWebhooks.class));
    }

    private String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + "/" + path;
    }

    private HttpServer start() {
        try {
            HttpServer started = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            started.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                bodies.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String signature = exchange.getRequestHeaders().getFirst(WebhookSignature.HEADER);
                if (signature != null) {
                    signatures.put(path, signature);
                }
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            started.start();
            return started;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
