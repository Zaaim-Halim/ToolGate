package io.toolgate.webhook;

import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.OnViolation.DEFER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.sun.net.httpserver.HttpServer;
import io.toolgate.api.ApprovalAccess;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.ToolCatalog;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What a receiver gets from a real gate: signed, retried, never an argument value. */
class ApprovalWebhookTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final Principal JOB = Principal.service("refund-agent", Tenant.of("acme"));
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private volatile int failWith = 503;
    private final HttpServer server = start();

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aReceiverGetsSignedNoticesWithALinkAndNoArgumentValues() {
        ApprovalWebhook webhook = ApprovalWebhook.signed(url(), SECRET).consoleUrl("https://app.example.com/toolgate/approvals/")
                .build();
        ToolGate gate = gate(webhook);
        String approvalId = defer(gate);
        gate.approve(approvalId, LENA, JOB, "ok, order 7731 checked");
        gate.close();
        webhook.close();

        assertThat(received).hasSize(2);
        Received deferred = received.getFirst();
        assertThat(deferred.body()).startsWith("{\"type\":\"approval.deferred\"")
                .contains("\"approvalId\":\"" + approvalId + "\"", "\"agent\":\"refund-agent\"",
                        "\"tool\":\"local/issueRefund\"", "\"tenant\":\"acme\"", "\"status\":\"pending\"",
                        "\"link\":\"https://app.example.com/toolgate/approvals/?id=refunds%3Ac1\"")
                .doesNotContain("7731", "480");
        assertThat(WebhookSignature.verify(SECRET, deferred.signature(), deferred.body(), Duration.ofMinutes(5))).isTrue();
        assertThat(received.get(1).body()).contains("\"type\":\"approval.decided\"", "\"status\":\"released\"",
                "\"by\":\"lena\"", "\"access\":\"reviewer\"").doesNotContain("7731");
        assertThat(webhook.delivered()).isEqualTo(2);
        assertThat(webhook.dropped()).isZero();
    }

    @Test
    void retriesAFailingReceiverWithTheSameDeliveryId() {
        failuresLeft.set(2);
        ApprovalWebhook webhook = ApprovalWebhook.signed(url(), SECRET).retries(3, Duration.ofMillis(10)).build();

        webhook.superAdminBurst(new AuditEvent.SuperAdminBurst(Instant.now(), 6, Duration.ofHours(1), "root"));
        webhook.close();

        assertThat(received).hasSize(3).extracting(Received::delivery).containsOnly(received.getFirst().delivery());
        assertThat(received.getLast().body()).contains("\"type\":\"superadmin.burst\"", "\"decisions\":6",
                "\"window\":\"PT1H\"", "\"by\":\"root\"");
        assertThat(webhook.delivered()).isEqualTo(1);
    }

    @Test
    void aRefusingReceiverIsNotRetried() {
        failuresLeft.set(10);
        failWith = 400;
        ApprovalWebhook webhook = ApprovalWebhook.signed(url(), SECRET).retries(5, Duration.ofMillis(10)).build();

        webhook.finished(new AuditEvent.ApprovalDecided(Instant.now(), "a-1", "s1", "refund-agent",
                ToolId.local("issueRefund"), ApprovalStatus.UNKNOWN, "toolgate", null, "lease ran out"));
        webhook.close();

        assertThat(received).hasSize(1);
        assertThat(webhook.dropped()).isEqualTo(1);
    }

    @Test
    void onlyTheChosenEventsAreSent() {
        ApprovalWebhook webhook = ApprovalWebhook.signed(url(), SECRET).events(ApprovalWebhook.Event.EXPIRING).build();

        webhook.decided(new AuditEvent.ApprovalDecided(Instant.now(), "a-1", "s1", "refund-agent",
                ToolId.local("issueRefund"), ApprovalStatus.REJECTED, "lena", ApprovalAccess.REVIEWER, "no"));
        webhook.expiringSoon(new AuditEvent.ApprovalExpiring(Instant.now(), "a-2", "s1", "refund-agent",
                ToolId.local("issueRefund"), Instant.parse("2026-10-12T10:00:00Z")));
        webhook.close();

        assertThat(received).singleElement().satisfies(delivery -> assertThat(delivery.body())
                .contains("\"type\":\"approval.expiring\"", "\"expiresAt\":\"2026-10-12T10:00:00Z\""));
    }

    @Test
    void slackGetsAReadableUnsignedMessage() {
        ApprovalWebhook webhook = ApprovalWebhook.slack(url()).consoleUrl("https://app.example.com/toolgate/approvals/").build();

        webhook.finished(new AuditEvent.ApprovalDecided(Instant.now(), "a-1", "s1", "<script>",
                ToolId.local("issueRefund"), ApprovalStatus.UNKNOWN, "toolgate", null, "lease ran out"));
        webhook.close();

        assertThat(received).singleElement().satisfies(delivery -> {
            assertThat(delivery.signature()).isNull();
            assertThat(delivery.body()).isEqualTo("{\"text\":\":question: Nobody knows whether `local/issueRefund` ran for"
                    + " *&lt;script&gt;*. Check the real system, then resolve it in the console."
                    + " <https://app.example.com/toolgate/approvals/?id=a-1|Open it>\"}");
        });
    }

    @Test
    void teamsGetsAnAdaptiveCardWithAButtonToTheConsole() {
        ApprovalWebhook webhook = ApprovalWebhook.teams(url()).consoleUrl("https://app.example.com/toolgate/approvals/").build();

        webhook.expiringSoon(new AuditEvent.ApprovalExpiring(Instant.now(), "a-1", "s1", "refund-agent",
                ToolId.local("issueRefund"), Instant.parse("2026-10-12T10:00:00Z")));
        webhook.close();

        assertThat(received).singleElement().satisfies(delivery -> {
            assertThat(delivery.signature()).isNull();
            assertThat(delivery.body()).isEqualTo("{\"type\":\"message\",\"attachments\":[{\"contentType\":"
                    + "\"application/vnd.microsoft.card.adaptive\",\"content\":{\"$schema\":"
                    + "\"http://adaptivecards.io/schemas/adaptive-card.json\",\"type\":\"AdaptiveCard\",\"version\":\"1.4\","
                    + "\"body\":[{\"type\":\"TextBlock\",\"text\":\"ToolGate\",\"weight\":\"Bolder\",\"size\":\"Small\","
                    + "\"isSubtle\":true},{\"type\":\"TextBlock\",\"text\":\"A request from **refund-agent** to run"
                    + " local/issueRefund expires at 2026-10-12T10:00:00Z unless someone decides.\",\"wrap\":true}],"
                    + "\"actions\":[{\"type\":\"Action.OpenUrl\",\"title\":\"Review it\","
                    + "\"url\":\"https://app.example.com/toolgate/approvals/?id=a-1\"}]}}]}");
        });
    }

    @Test
    void refusesPlainHttpToAnotherHostAndAShortSecret() {
        assertThatIllegalArgumentException().isThrownBy(() -> ApprovalWebhook.slack(URI.create("http://hooks.slack.com/x")));
        assertThatIllegalArgumentException().isThrownBy(() -> ApprovalWebhook.signed(url(), new byte[16]));
    }

    private ToolGate gate(ApprovalWebhook webhook) {
        var catalog = ToolCatalog.inMemory().put(ToolDefinition.of(ToolId.local("issueRefund"), "Refund an order",
                "{\"type\":\"object\",\"properties\":{\"order\":{},\"amount\":{}}}"));
        var policy = ToolGatePolicy.builder()
                .agent("refund-agent", a -> a.allow("issueRefund").reviewers("finance")
                        .rule("issueRefund.amount", atMost("1000"), atMost("200").otherwise(DEFER)))
                .build();
        return ToolGate.builder(policy).toolCatalog(catalog).noDefaultLog().approvalListener(webhook).build();
    }

    private static String defer(ToolGate gate) {
        var call = new ToolCall("c1", JOB, "refund-agent", "refunds", ToolId.local("issueRefund"),
                ObjectValue.of(Map.of("order", ArgValue.number(7731), "amount", ArgValue.number(480))), Instant.now());
        return ((Decision.Defer) gate.check(call)).approvalId();
    }

    private URI url() {
        return URI.create("http://localhost:" + server.getAddress().getPort() + "/hook");
    }

    private HttpServer start() {
        try {
            HttpServer started = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            started.createContext("/hook", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.add(new Received(exchange.getRequestHeaders().getFirst(WebhookSignature.HEADER),
                        exchange.getRequestHeaders().getFirst(WebhookSignature.DELIVERY_HEADER), body));
                int status = failuresLeft.getAndDecrement() > 0 ? failWith : 204;
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            started.start();
            return started;
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Received(String signature, String delivery, String body) {}
}
