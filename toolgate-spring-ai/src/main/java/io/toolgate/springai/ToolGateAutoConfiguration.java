package io.toolgate.springai;

import io.toolgate.api.ToolGate;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.AuditSink;
import io.toolgate.spi.HostResolver;
import io.toolgate.spi.OwnershipResolver;
import io.toolgate.spi.SessionStore;
import io.toolgate.spi.ToolCatalog;
import java.time.Clock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Puts ToolGate between Spring AI and every tool. The application supplies a {@link ToolGatePolicy}
 * bean, and may supply a {@link SessionStore}, {@link ApprovalQueue}, {@link AuditSink},
 * {@link OwnershipResolver}, {@link HostResolver} or {@link Clock}; everything else is wired here.
 *
 * <p>Spring Boot's own {@link ToolCallingManager} and {@link ToolCallbackResolver} are wrapped rather
 * than replaced, so their configuration stays as the application set it, and Spring Boot's
 * {@code ChatClient.Builder} gets an advisor that gates each request's tools. Without a policy bean,
 * startup fails: an application that added ToolGate must never run unchecked.
 */
@AutoConfiguration
@ConditionalOnClass(ChatClient.class)
public class ToolGateAutoConfiguration {

    /** Creates the configuration; Spring Boot does this. */
    public ToolGateAutoConfiguration() {}

    /**
     * Tool definitions as the model is offered them, for pins and declared arguments.
     *
     * @return an empty catalog, filled as tools are called
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolCatalog.Mutable toolGateToolCatalog() {
        return ToolCatalog.inMemory();
    }

    /**
     * The gate.
     *
     * @param policy    the application's policy; required
     * @param catalog   the tool catalog
     * @param sessions  a session store, or the in-memory default
     * @param approvals an approval queue, or the in-memory default
     * @param audit     an audit sink, or the JDK logger
     * @param owners    an ownership resolver, required by ownership rules
     * @param hosts     a host resolver, to check where URL hosts resolve
     * @param clock     a clock, or the system clock
     * @return the gate, closed when the application stops
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ToolGate toolGate(ObjectProvider<ToolGatePolicy> policy, ToolCatalog.Mutable catalog,
                             ObjectProvider<SessionStore> sessions, ObjectProvider<ApprovalQueue> approvals,
                             ObjectProvider<AuditSink> audit, ObjectProvider<OwnershipResolver> owners,
                             ObjectProvider<HostResolver> hosts, ObjectProvider<Clock> clock) {
        ToolGatePolicy required = policy.getIfAvailable();
        if (required == null) {
            throw new IllegalStateException("toolgate-spring-ai needs a ToolGatePolicy bean; without one, every tool"
                    + " would run unchecked, so the application does not start");
        }
        ToolGate.Builder builder = ToolGate.builder(required).toolCatalog(catalog).clock(clock.getIfAvailable(Clock::systemUTC));
        sessions.ifAvailable(builder::sessionStore);
        approvals.ifAvailable(builder::approvalQueue);
        audit.ifAvailable(builder::auditSink);
        owners.ifAvailable(builder::ownershipResolver);
        hosts.ifAvailable(builder::hostResolver);
        return builder.build();
    }

    @Bean
    Gating toolGateGating(ToolGate gate, ToolCatalog.Mutable catalog, ObjectProvider<ToolCallbackResolver> resolver,
                          ObjectProvider<Clock> clock) {
        return new Gating(gate, catalog, clock.getIfAvailable(Clock::systemUTC), resolver.getIfAvailable());
    }

    /**
     * Wraps the application's tool calling manager and callback resolver as they are created.
     *
     * @param gating resolved when the first tool runs, so the gate is built after the beans it needs
     * @return the post-processor
     */
    @Bean
    static BeanPostProcessor toolGatePostProcessor(ObjectProvider<Gating> gating) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return switch (bean) {
                    case GatedToolCallingManager gated -> gated;
                    case GatedToolCallbackResolver gated -> gated;
                    case ToolCallingManager manager -> new GatedToolCallingManager(manager, gating::getObject);
                    case ToolCallbackResolver resolver -> new GatedToolCallbackResolver(resolver, gating::getObject);
                    default -> bean;
                };
            }
        };
    }

    /**
     * Adds ToolGate's advisor to Spring Boot's {@code ChatClient.Builder}.
     *
     * @param gating the shared gating
     * @return the customizer
     */
    @Bean
    public ChatClientBuilderCustomizer toolGateChatClientCustomizer(ObjectProvider<Gating> gating) {
        return builder -> builder.defaultAdvisors(new ToolGateAdvisor(gating::getObject));
    }

    /**
     * Gated chat clients for applications that build them by hand.
     *
     * @param manager the gated tool calling manager, resolved when a client is built
     * @param gating  the shared gating
     * @return the factory
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolGateChatClients toolGateChatClients(ObjectProvider<ToolCallingManager> manager, ObjectProvider<Gating> gating) {
        return new ToolGateChatClients(manager::getObject, gating::getObject);
    }
}
