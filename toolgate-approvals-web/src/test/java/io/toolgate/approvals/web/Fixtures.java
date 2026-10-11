package io.toolgate.approvals.web;

import static io.toolgate.api.Label.EXTERNAL_SINK;
import static io.toolgate.api.Label.UNTRUSTED_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.Effect;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.ToolResult;
import io.toolgate.api.policy.Mask;
import io.toolgate.api.policy.TaintAction;
import io.toolgate.api.policy.ToolGatePolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;

/**
 * What both test applications share: Case F's refund agent, a refund tool any instance can run, and
 * reviewers in tenants. A refund after reading a return request waits for finance.
 */
@Configuration(proxyBeanMethods = false)
class Fixtures {

    static final Principal JOB = Principal.service("refund-job", Tenant.of("acme"));
    static final Map<String, String> TENANT_OF = Map.of("lena", "acme", "gus", "globex", "root", "operator");
    static final List<String> REFUNDS = new CopyOnWriteArrayList<>();
    /** Who Spring Security says is signed in while each refund runs: nobody, whoever approved it. */
    static final List<String> RAN_AS = new CopyOnWriteArrayList<>();

    @Bean
    ToolGatePolicy policy() {
        return ToolGatePolicy.builder()
                .superAdmins("toolgate-admin")
                .tool("readReturnRequest", t -> t.labels(UNTRUSTED_CONTENT).effect(Effect.READ))
                .tool("issueRefund", t -> t.labels(EXTERNAL_SINK))
                .tool("lookupCustomer", t -> t.labels(io.toolgate.api.Label.PRIVATE_DATA).effect(Effect.READ))
                .tool("sendEmail", t -> t.labels(EXTERNAL_SINK))
                .tool("draftEmail", t -> t.effect(Effect.REVERSIBLE))
                .agent("refund-agent", a -> a
                        .allow("readReturnRequest", "issueRefund", "lookupCustomer", "sendEmail", "draftEmail")
                        .downgrade("sendEmail", "draftEmail")
                        .onTrifecta(TaintAction.DEFER)
                        .reviewers("finance")
                        .review("issueRefund", r -> r.title("Refund an order").show("order", "amount", "note")
                                .mask("iban", Mask.last(4))))
                .build();
    }

    @Bean
    ToolCallbackProvider refundTools() {
        return MethodToolCallbackProvider.builder().toolObjects(new RefundTools()).build();
    }

    /** Reviewers in tenants, as a multi-tenant application maps its users. */
    @Bean
    ToolGateReviewerResolver reviewers() {
        return authentication -> Principal.user(authentication.getName(),
                Tenant.of(TENANT_OF.getOrDefault(authentication.getName(), "nowhere")),
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                        .filter(authority -> !authority.startsWith("FACTOR_"))
                        .map(role -> role.replaceFirst("^ROLE_", "")).toArray(String[]::new));
    }

    /** The refund tool, registered as a bean so any instance can run an approved refund. */
    static final class RefundTools {

        @Tool(description = "Refund an order")
        String issueRefund(String order, int amount, String note, String iban) {
            REFUNDS.add(order + ":" + amount);
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            RAN_AS.add(authentication == null ? "nobody" : authentication.getName());
            return "refunded " + amount;
        }
    }

    /** Defers a refund the way an agent would: after reading an untrusted return request. */
    static String deferRefund(ToolGate gate, String session, String note) {
        ToolCall read = new ToolCall(session + "-read", JOB, "refund-agent", session, ToolId.local("readReturnRequest"),
                ObjectValue.empty(), Instant.now());
        gate.check(read);
        gate.recordResult(read, new ToolResult.Succeeded(ArgValue.text("please refund order 7731")));
        Decision decision = gate.check(new ToolCall(session + "-refund", JOB, "refund-agent", session,
                ToolId.local("issueRefund"), ObjectValue.of(Map.of("order", ArgValue.text("7731"),
                        "amount", ArgValue.number(480), "note", ArgValue.text(note),
                        "iban", ArgValue.text("DE89370400440532013000"))), Instant.now()));
        assertThat(decision).isInstanceOf(Decision.Defer.class);
        return ((Decision.Defer) decision).approvalId();
    }
}
