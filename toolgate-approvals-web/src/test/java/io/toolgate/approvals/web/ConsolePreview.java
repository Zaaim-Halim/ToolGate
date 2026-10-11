package io.toolgate.approvals.web;

import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.ToolResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts the console on http://localhost:8080/toolgate/approvals with sample calls, to look at it in a
 * browser. Users: lena / lena-pass (finance, tenant acme), gus / gus-pass (finance, tenant globex),
 * root / root-pass (super admin). Not a test; run its main method from the IDE or the command line.
 */
public final class ConsolePreview {

    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");

    private ConsolePreview() {}

    public static void main(String[] args) throws InterruptedException {
        ConfigurableApplicationContext context = SpringApplication.run(BrowserTestApplication.class,
                "--server.port=" + (args.length > 0 ? args[0] : "8080"),
                "--toolgate.approvals.ui.enabled=true",
                "--toolgate.approvals.ui.application=Refund desk (preview)",
                "--spring.ai.openai.api-key=preview",
                "--toolgate.approvals.executor.enabled=false");
        ToolGate gate = context.getBean(ToolGate.class);

        defer(gate, Fixtures.JOB, "7731", 480, "Item arrived broken, customer sent photos.");
        defer(gate, Fixtures.JOB, "7744", 1250, "Refund to bi11ing@pаypal.com <img src=x onerror=alert('pwned')>");
        defer(gate, Fixtures.JOB, "7802", 89, "Duplicate charge on the March invoice.");

        String rejected = defer(gate, Fixtures.JOB, "7690", 300, "Customer asks again for order 7690.");
        gate.reject(rejected, LENA, "Already refunded on 2026-10-02, ticket 48190.");

        String executed = defer(gate, Fixtures.JOB, "7655", 120, "Late delivery compensation.");
        gate.approve(executed, LENA, Fixtures.JOB, "Policy allows late-delivery refunds.");
        gate.claim(executed, "preview-node", Duration.ofMinutes(1));
        gate.finished(executed, "preview-node", new ToolResult.Succeeded(ArgValue.text("refund R-2201")));

        String lost = defer(gate, Fixtures.JOB, "7701", 640, "Wrong size, return received.");
        gate.approve(lost, LENA, Fixtures.JOB, "Return confirmed by the warehouse.");
        gate.claim(lost, "crashed-node", Duration.ofMillis(1));
        Thread.sleep(20);
        gate.sweep();

        defer(gate, Principal.service("refund-job", Tenant.of("globex")), "G-118", 75, "Another tenant's refund.");

        System.out.println("""

                ToolGate console preview: http://localhost:%s/toolgate/approvals
                  lena / lena-pass   finance reviewer, tenant acme
                  gus  / gus-pass    finance reviewer, tenant globex
                  root / root-pass   super admin (decisions need a comment)
                """.formatted(args.length > 0 ? args[0] : "8080"));
    }

    private static String defer(ToolGate gate, Principal job, String order, int amount, String note) {
        String session = "preview-" + UUID.randomUUID();
        ToolCall read = new ToolCall(session + "-read", job, "refund-agent", session, ToolId.local("readReturnRequest"),
                ObjectValue.empty(), Instant.now());
        gate.check(read);
        gate.recordResult(read, new ToolResult.Succeeded(ArgValue.text("please refund order " + order)));
        Decision decision = gate.check(new ToolCall(session + "-refund", job, "refund-agent", session,
                ToolId.local("issueRefund"), ObjectValue.of(Map.of("order", ArgValue.text(order),
                        "amount", ArgValue.number(amount), "note", ArgValue.text(note),
                        "iban", ArgValue.text("DE89370400440532013000"))), Instant.now()));
        return ((Decision.Defer) decision).approvalId();
    }
}
