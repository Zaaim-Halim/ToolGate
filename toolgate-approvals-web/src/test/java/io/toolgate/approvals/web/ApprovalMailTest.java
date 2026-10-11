package io.toolgate.approvals.web;

import static io.toolgate.api.policy.ArgumentRules.atMost;
import static io.toolgate.api.policy.OnViolation.DEFER;
import static org.assertj.core.api.Assertions.assertThat;

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
import io.toolgate.springai.ToolGateAutoConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

/** Approval mail: who gets what, with a link and never an argument value. */
class ApprovalMailTest {

    private static final Principal JOB = Principal.service("refund-agent", Tenant.of("acme"));

    private final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();
    private final MailSender capturing = new MailSender() {
        @Override
        public void send(SimpleMailMessage message) {
            sent.add(message);
        }

        @Override
        public void send(SimpleMailMessage... messages) {
            sent.addAll(List.of(messages));
        }
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolGateAutoConfiguration.class, ToolGateApprovalMailAutoConfiguration.class))
            .withBean(ToolGatePolicy.class, () -> ToolGatePolicy.builder()
                    .agent("refund-agent", a -> a.allow("issueRefund").reviewers("finance")
                            .rule("issueRefund.amount", atMost("1000"), atMost("200").otherwise(DEFER)))
                    .build())
            .withBean(ToolCatalog.Mutable.class, () -> ToolCatalog.inMemory().put(ToolDefinition.of(ToolId.local("issueRefund"),
                    "Refund an order", "{\"type\":\"object\",\"properties\":{\"order\":{},\"amount\":{}}}")))
            .withPropertyValues("toolgate.audit.log.enabled=false", "toolgate.approvals.executor.enabled=false");

    @Test
    void reviewersOfTheAgentHearOfAWaitingCallWithALink() {
        runner.withBean(MailSender.class, () -> capturing)
                .withPropertyValues("toolgate.approvals.notify.email.enabled=true",
                        "toolgate.approvals.notify.email.from=toolgate@refunds.example.com",
                        "toolgate.approvals.notify.email.to=ops@example.com",
                        "toolgate.approvals.notify.email.agents.refund-agent=finance@example.com",
                        "toolgate.approvals.notify.console-url=https://refunds.example.com/toolgate/approvals/")
                .run(context -> {
                    ToolGate gate = context.getBean(ToolGate.class);
                    var call = new ToolCall("c1", JOB, "refund-agent", "refunds", ToolId.local("issueRefund"),
                            ObjectValue.of(Map.of("order", ArgValue.number(7731), "amount", ArgValue.number(480))), Instant.now());
                    String approvalId = ((Decision.Defer) gate.check(call)).approvalId();
                    gate.close();
                    context.getBean(ApprovalMailer.class).close();

                    assertThat(sent).singleElement().satisfies(mail -> {
                        assertThat(mail.getFrom()).isEqualTo("toolgate@refunds.example.com");
                        assertThat(mail.getTo()).containsExactly("ops@example.com", "finance@example.com");
                        assertThat(mail.getSubject()).isEqualTo("[ToolGate] refund-agent asks to run local/issueRefund");
                        assertThat(mail.getText()).contains("https://refunds.example.com/toolgate/approvals/?id="
                                + approvalId.replace(":", "%3A")).doesNotContain("7731", "480");
                    });
                });
    }

    @Test
    void anUnknownOutcomeAndABurstReachTheGeneralList() {
        runner.withBean(MailSender.class, () -> capturing)
                .withPropertyValues("toolgate.approvals.notify.email.enabled=true",
                        "toolgate.approvals.notify.email.from=toolgate@refunds.example.com",
                        "toolgate.approvals.notify.email.to=ops@example.com",
                        "toolgate.approvals.notify.email.agents.other-agent=other@example.com")
                .run(context -> {
                    ApprovalMailer mailer = context.getBean(ApprovalMailer.class);
                    mailer.finished(new AuditEvent.ApprovalDecided(Instant.now(), "a-1", "s1", "refund-agent",
                            ToolId.local("issueRefund"), ApprovalStatus.EXECUTED, "node-1", null, "ran"));
                    mailer.finished(new AuditEvent.ApprovalDecided(Instant.now(), "a-2", "s1", "refund-agent",
                            ToolId.local("issueRefund"), ApprovalStatus.UNKNOWN, "toolgate", null, "lease ran out"));
                    mailer.superAdminBurst(new AuditEvent.SuperAdminBurst(Instant.now(), 6, Duration.ofHours(1), "root"));
                    mailer.close();

                    assertThat(sent).extracting(SimpleMailMessage::getSubject).containsExactly(
                            "[ToolGate] Outcome unknown: local/issueRefund for refund-agent",
                            "[ToolGate] Super admin burst: 6 decisions within PT1H");
                    assertThat(sent.getFirst().getText()).contains("Approval ID: a-2");
                    assertThat(sent).allSatisfy(mail -> assertThat(mail.getTo()).containsExactly("ops@example.com"));
                });
    }

    @Test
    void offUnlessTurnedOnAndRefusesToStartHalfConfigured() {
        runner.withBean(MailSender.class, () -> capturing)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ApprovalMailer.class));
        runner.withPropertyValues("toolgate.approvals.notify.email.enabled=true",
                        "toolgate.approvals.notify.email.from=toolgate@refunds.example.com",
                        "toolgate.approvals.notify.email.to=ops@example.com")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("needs a MailSender bean"));
        runner.withBean(MailSender.class, () -> capturing)
                .withPropertyValues("toolgate.approvals.notify.email.enabled=true", "toolgate.approvals.notify.email.to=ops@example.com")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("notify.email.from is required"));
    }
}
