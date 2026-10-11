package io.toolgate.springai;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.toolgate.api.ToolGate;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.ApprovalListener;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.AuditSink;
import io.toolgate.spi.DecisionObserver;
import io.toolgate.spi.HostResolver;
import io.toolgate.spi.OwnershipResolver;
import io.toolgate.spi.SessionStore;
import io.toolgate.spi.ToolCatalog;
import io.toolgate.telemetry.MicrometerAuditSink;
import io.toolgate.telemetry.MicrometerCheckTimer;
import io.toolgate.telemetry.OpenTelemetryDecisions;
import java.time.Clock;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts ToolGate between Spring AI and every tool. The application supplies a {@link ToolGatePolicy}
 * bean, and may supply a {@link SessionStore}, {@link ApprovalQueue}, {@link OwnershipResolver},
 * {@link HostResolver} or {@link Clock}, and any number of {@link AuditSink} and
 * {@link DecisionObserver} beans; everything else is wired here.
 *
 * <p>Decisions go to the {@code toolgate.audit} log unless {@code toolgate.audit.log.enabled=false}. Micrometer
 * metrics ({@code toolgate.audit.micrometer.enabled=true}) and OpenTelemetry span attributes
 * ({@code toolgate.audit.opentelemetry.enabled=true}) are off until the application turns them on.
 *
 * <p>Spring Boot's own {@link ToolCallingManager} and {@link ToolCallbackResolver} are wrapped rather
 * than replaced, so their configuration stays as the application set it, and Spring Boot's
 * {@code ChatClient.Builder} gets an advisor that gates each request's tools. Without a policy bean,
 * startup fails: an application that added ToolGate must never run unchecked.
 */
@AutoConfiguration
@ConditionalOnClass(ChatClient.class)
@EnableConfigurationProperties(ToolGateProperties.class)
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
     * @param audit     audit sinks, each given every event, besides the log
     * @param observers decision observers, each told of every check
     * @param listeners approval listeners, each told what happens to deferred calls
     * @param owners    an ownership resolver, required by ownership rules
     * @param hosts     a host resolver, to check where URL hosts resolve
     * @param clock     a clock, or the system clock
     * @param properties the {@code toolgate.*} settings
     * @return the gate, closed when the application stops
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ToolGate toolGate(ObjectProvider<ToolGatePolicy> policy, ToolCatalog.Mutable catalog,
                             ObjectProvider<SessionStore> sessions, ObjectProvider<ApprovalQueue> approvals,
                             ObjectProvider<AuditSink> audit, ObjectProvider<DecisionObserver> observers,
                             ObjectProvider<ApprovalListener> listeners,
                             ObjectProvider<OwnershipResolver> owners, ObjectProvider<HostResolver> hosts,
                             ObjectProvider<Clock> clock, ToolGateProperties properties) {
        ToolGatePolicy required = policy.getIfAvailable();
        if (required == null) {
            throw new IllegalStateException("toolgate-spring-ai needs a ToolGatePolicy bean; without one, every tool"
                    + " would run unchecked, so the application does not start");
        }
        ToolGate.Builder builder = ToolGate.builder(required).toolCatalog(catalog).clock(clock.getIfAvailable(Clock::systemUTC));
        sessions.ifAvailable(builder::sessionStore);
        approvals.ifAvailable(builder::approvalQueue);
        if (!properties.audit().log().enabled()) {
            builder.noDefaultLog();
        }
        audit.orderedStream().forEach(builder::auditSink);
        observers.orderedStream().forEach(builder::decisionObserver);
        listeners.orderedStream().forEach(builder::approvalListener);
        owners.ifAvailable(builder::ownershipResolver);
        hosts.ifAvailable(builder::hostResolver);
        return builder.build();
    }

    @Bean
    Gating toolGateGating(ToolGate gate, ToolGatePolicy policy, ToolCatalog.Mutable catalog,
                          ObjectProvider<ToolCallbackResolver> resolver, ObjectProvider<Clock> clock,
                          ObjectProvider<ToolGatePrincipalResolver> principals, ToolGateProperties properties) {
        return new Gating(gate, catalog, clock.getIfAvailable(Clock::systemUTC), resolver.getIfAvailable(), policy,
                principals.getIfAvailable(), properties.agent());
    }

    /** The signed-in user as the principal, in applications with Spring Security. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    static class SpringSecurity {

        @Bean
        @ConditionalOnMissingBean
        ToolGatePrincipalResolver toolGatePrincipalResolver() {
            return new SpringSecurityPrincipals();
        }
    }

    /**
     * Micrometer metrics, when the application turns them on: counter {@code toolgate.decisions}, timer
     * {@code toolgate.check.duration}, and function counter {@code toolgate.audit.dropped} for audit gaps.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnProperty(prefix = "toolgate.audit.micrometer", name = "enabled", havingValue = "true")
    static class Micrometer {

        /** The counter of {@link ToolGate#droppedAuditEvents()}. */
        static final String DROPPED = "toolgate.audit.dropped";

        @Bean
        @ConditionalOnMissingBean
        MicrometerAuditSink toolGateMicrometerAuditSink(ObjectProvider<MeterRegistry> registry, ToolGatePolicy policy) {
            return new MicrometerAuditSink(requireRegistry(registry), policy);
        }

        @Bean
        @ConditionalOnMissingBean
        MicrometerCheckTimer toolGateMicrometerCheckTimer(ObjectProvider<MeterRegistry> registry) {
            return new MicrometerCheckTimer(requireRegistry(registry));
        }

        // The gate is looked up when the counter is read, never while the registry is being set up: the
        // gate's own sinks need the registry, so binding it eagerly would be a cycle.
        @Bean
        MeterBinder toolGateDroppedAuditEvents(ObjectProvider<ToolGate> gate) {
            return registry -> FunctionCounter.builder(DROPPED, gate, provider -> provider.getObject().droppedAuditEvents())
                    .description("Audit events and observer calls ToolGate dropped; enforcement is unaffected")
                    .register(registry);
        }

        private static MeterRegistry requireRegistry(ObjectProvider<MeterRegistry> registry) {
            MeterRegistry available = registry.getIfAvailable();
            if (available == null) {
                throw new IllegalStateException("toolgate.audit.micrometer.enabled=true needs a MeterRegistry bean,"
                        + " such as Spring Boot's with spring-boot-starter-actuator");
            }
            return available;
        }
    }

    /** Decisions on the current OpenTelemetry span, when the application turns them on. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.opentelemetry.api.trace.Span")
    @ConditionalOnProperty(prefix = "toolgate.audit.opentelemetry", name = "enabled", havingValue = "true")
    static class OpenTelemetry {

        @Bean
        @ConditionalOnMissingBean
        OpenTelemetryDecisions toolGateOpenTelemetryDecisions() {
            return new OpenTelemetryDecisions();
        }
    }

    /**
     * Wraps the application's tool calling manager and callback resolver as they are created, and guards
     * the application's tool beans against chat clients that run them without ToolGate.
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
                    default -> ToolBeanGuard.guard(bean, beanName, gating::getObject);
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
     * Decides deferred calls and runs the approved ones.
     *
     * @param gating     the shared gating
     * @param properties the {@code toolgate.*} settings, for the executor's name and lease
     * @return the approvals
     */
    @Bean
    @ConditionalOnMissingBean
    public ToolGateApprovals toolGateApprovals(ObjectProvider<Gating> gating, ToolGateProperties properties) {
        ToolGateProperties.Executor executor = properties.approvals().executor();
        return new ToolGateApprovals(gating::getObject, executor.resolvedId(), executor.lease());
    }

    /**
     * Tells the agent, through chat memory, what became of the calls it asked for, when the application
     * turns it on.
     *
     * @param memory the application's chat memory
     * @return the listener
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "toolgate.approvals.outcome", name = "chat-memory", havingValue = "true")
    public ChatMemoryOutcomes toolGateChatMemoryOutcomes(ObjectProvider<ChatMemory> memory) {
        ChatMemory available = memory.getIfAvailable();
        if (available == null) {
            throw new IllegalStateException("toolgate.approvals.outcome.chat-memory=true needs a ChatMemory bean");
        }
        return new ChatMemoryOutcomes(available);
    }

    /**
     * Sweeps the approval queue and runs calls approved elsewhere, on a schedule.
     *
     * @param gate       the gate
     * @param approvals  the approvals
     * @param properties the {@code toolgate.*} settings, for the interval
     * @return the executor, started and stopped with the application
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "toolgate.approvals.executor", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ToolGateApprovalExecutor toolGateApprovalExecutor(ToolGate gate, ToolGateApprovals approvals,
                                                             ToolGateProperties properties) {
        return new ToolGateApprovalExecutor(gate, approvals, properties.approvals().executor().interval());
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
