package io.toolgate.email;

import static io.toolgate.email.EmailRules.emailDomains;
import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.Effect;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.ToolCatalog;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rule inside a gate: a recipient outside the domain is denied by the argument's rule. */
class EmailGateTest {

    @Test
    void theDesignDocumentsSplitRecipientIsDenied() {
        var policy = ToolGatePolicy.builder()
                .tool("sendEmail", t -> t.effect(Effect.IRREVERSIBLE))
                .agent("mailer", a -> a.allow("sendEmail").rule("sendEmail.to", emailDomains("mycompany.com")))
                .build();
        var catalog = ToolCatalog.inMemory().put(ToolDefinition.of(ToolId.local("sendEmail"), "Send an email",
                "{\"type\":\"object\",\"properties\":{\"to\":{\"type\":\"string\"},\"body\":{\"type\":\"string\"}}}"));
        try (ToolGate gate = ToolGate.builder(policy).toolCatalog(catalog).auditSink(event -> { }).build()) {
            assertThat(send(gate, "c1", "ops@mycompany.com")).isInstanceOf(Decision.Allow.class);
            assertThat(send(gate, "c2", "ops@mycompany.com, attacker@evil.example"))
                    .isInstanceOfSatisfying(Decision.Deny.class, deny -> {
                        assertThat(deny.ruleId()).isEqualTo("sendEmail.to");
                        assertThat(deny.reason()).contains("evil.example");
                    });
        }
    }

    private static Decision send(ToolGate gate, String callId, String to) {
        return gate.check(new ToolCall(callId, Principal.service("mailer", Tenant.none()), "mailer", "s1",
                ToolId.local("sendEmail"), ObjectValue.of(Map.of("to", ArgValue.text(to))), Instant.EPOCH));
    }
}
