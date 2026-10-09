package io.toolgate.springai;

import static io.toolgate.api.Effect.IRREVERSIBLE;
import static io.toolgate.api.Effect.READ;
import static io.toolgate.api.Effect.REVERSIBLE;
import static io.toolgate.api.Label.EXTERNAL_SINK;
import static io.toolgate.api.Label.PRIVATE_DATA;
import static io.toolgate.api.Label.UNTRUSTED_CONTENT;
import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.OnViolation.DEFER;

import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.AuditSink;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** A Spring AI application that added toolgate-spring-ai and a policy, and nothing else. */
@SpringBootApplication
class TestApplication {

    static final List<AuditEvent> AUDIT = new CopyOnWriteArrayList<>();

    @Bean
    ToolGatePolicy policy() {
        return ToolGatePolicy.builder()
                .tool("getWeather", t -> t.effect(READ))
                .tool("fetchPage", t -> t.labels(UNTRUSTED_CONTENT, EXTERNAL_SINK).effect(READ))
                .tool("readInvoice", t -> t.labels(PRIVATE_DATA).effect(READ))
                .tool("sendEmail", t -> t.labels(EXTERNAL_SINK).effect(IRREVERSIBLE))
                .tool("draftEmail", t -> t.effect(REVERSIBLE))
                .agent("support-chat", a -> a
                        .allow("getWeather", "fetchPage", "readInvoice", "sendEmail", "draftEmail")
                        .rule("fetchPage.url", hosts("docs.example"))
                        .downgrade("sendEmail", "draftEmail"))
                .tool("readTicket", t -> t.labels(UNTRUSTED_CONTENT).effect(READ))
                .tool("issueRefund", t -> t.effect(IRREVERSIBLE))
                .tool("createPendingRefund", t -> t.effect(REVERSIBLE))
                .agent("refunds", a -> a
                        .allow("readTicket", "issueRefund", "createPendingRefund")
                        .downgrade("issueRefund", "createPendingRefund"))
                .mcpServers("jira")
                .agent("triage", a -> a.allow("mcp:jira/searchTickets"))
                .tool("fetchStatus", t -> t.labels(UNTRUSTED_CONTENT).effect(READ))
                .agent("refund-review", a -> a
                        .allow("issueRefund")
                        .reviewers("finance")
                        .rule("issueRefund.amount", atMost("1000"), atMost("200").otherwise(DEFER)))
                .agent("probe", a -> a
                        .allow("readInvoice", "fetchStatus", "checkQuota", "sendEmail", "draftEmail")
                        .downgrade("sendEmail", "draftEmail"))
                .agent("three-calls", a -> a
                        .allow("getWeather")
                        .budget(b -> b.toolCalls(3)))
                .agent("weather-desk", a -> a
                        .allow("getWeather", "fetchStatus")
                        .budget(b -> b.toolCalls(2)))
                .agent("nightly-weather", a -> a
                        .allow("getWeather")
                        .principal(io.toolgate.api.Principal.service("weather-job", io.toolgate.api.Tenant.of("acme"))))
                .agent("chat", a -> a
                        .allow("getWeather")
                        .interactive(true))
                .tool("queueRefund", t -> t.effect(REVERSIBLE))
                .agent("refunds-ruled", a -> a
                        .allow("readTicket", "issueRefund", "queueRefund")
                        .downgrade("issueRefund", "queueRefund")
                        .rule("queueRefund.amount", atMost("1000")))
                .agent("one-call", a -> a
                        .allow("getWeather")
                        .budget(b -> b.toolCalls(1)))
                .build();
    }

    @Bean
    AuditSink auditSink() {
        return AUDIT::add;
    }
}
