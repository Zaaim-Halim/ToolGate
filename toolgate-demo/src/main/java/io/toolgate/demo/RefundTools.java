package io.toolgate.demo;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** Case F's tool: it refunds whatever it is asked to, so ToolGate decides how much waits for finance. */
@Component
public class RefundTools {

    private static final List<String> ISSUED = new CopyOnWriteArrayList<>();

    @Tool(description = "Refund an order")
    String issueRefund(String order, int amount) {
        ISSUED.add(order + ":" + amount);
        return "refunded " + amount + " on order " + order;
    }

    /** @return the refunds issued, as order:amount, across application contexts */
    public static List<String> issued() {
        return List.copyOf(ISSUED);
    }

    /** Forgets the refunds issued, between runs. */
    public static void clear() {
        ISSUED.clear();
    }
}
