package io.toolgate.springai;

import static io.toolgate.api.Effect.IRREVERSIBLE;
import static io.toolgate.api.Effect.READ;
import static io.toolgate.api.Effect.REVERSIBLE;
import static io.toolgate.api.Label.EXTERNAL_SINK;
import static io.toolgate.api.Label.PRIVATE_DATA;
import static io.toolgate.api.Label.UNTRUSTED_CONTENT;
import static io.toolgate.api.policy.ArgumentRules.hosts;

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
