package io.toolgate.test;

import io.toolgate.api.policy.ToolGatePolicy;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Gives every test method of the class a fresh {@link ScriptedSession} parameter.
 *
 * <pre>{@code
 * @ToolGateTest(policy = InvoicePolicy.class, agent = "invoice-processor",
 *               principal = "service:invoice-processor@acme")
 * class InvoiceProcessorPolicyTest {
 *     @Test
 *     void cannotReadAnotherTenantsInvoice(ScriptedSession s) {
 *         s.tenantOf("invoice", "INV-900", "globex");
 *         s.call("readInvoice", Map.of("id", "INV-900")).assertDeniedBy("readInvoice.id");
 *     }
 * }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(ToolGateExtension.class)
public @interface ToolGateTest {

    /**
     * Supplies the policy under test: a class with a no-argument constructor.
     *
     * @return the supplier class
     */
    Class<? extends Supplier<ToolGatePolicy>> policy();

    /**
     * The agent making the calls.
     *
     * @return its ID, as in the policy
     */
    String agent();

    /**
     * Who the agent acts for: {@code kind:id[@tenant][#role,role]}, such as
     * {@code service:invoice-processor@acme} or {@code user:maria@acme#support,finance}.
     *
     * @return the principal
     */
    String principal();
}
