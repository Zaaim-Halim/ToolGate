package io.toolgate.approvals.web;

import io.toolgate.springai.ToolGateApprovals;
import io.toolgate.springai.ToolGateAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The review console and its REST API, when the application turns them on with
 * {@code toolgate.approvals.ui.enabled=true}: at {@code /toolgate/approvals/} and
 * {@code /toolgate/api/approvals}, on Spring MVC or WebFlux.
 *
 * <p>Spring Security is required: without it the application does not start, since a console that
 * decides refunds must never be open. Who signs in, and how, is the application's own security setup;
 * every endpoint also refuses anyone not signed in, even if that setup forgot the path.
 */
@AutoConfiguration(after = ToolGateAutoConfiguration.class)
@ConditionalOnProperty(prefix = "toolgate.approvals.ui", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ConsoleProperties.class)
public class ToolGateApprovalsWebAutoConfiguration {

    static final String SPRING_SECURITY = "org.springframework.security.core.Authentication";
    static final String SERVLET_SECURITY = "org.springframework.security.web.SecurityFilterChain";
    static final String REACTIVE_SECURITY = "org.springframework.security.web.server.SecurityWebFilterChain";
    static final String UI_LOCATION = "classpath:/toolgate-ui/";
    static final String UI_PATH = "/toolgate/approvals/**";

    /** Creates the configuration; Spring Boot does this. */
    public ToolGateApprovalsWebAutoConfiguration() {}

    /** The API's rules, with the application's reviewer resolver and requester directory or the defaults. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = SPRING_SECURITY)
    static class Api {

        @Bean
        @ConditionalOnMissingBean
        ToolGateReviewerResolver toolGateReviewerResolver() {
            return new AuthorityReviewerResolver();
        }

        @Bean
        @ConditionalOnMissingBean
        ToolGateRequesterDirectory toolGateRequesterDirectory() {
            return asDeferred -> asDeferred;
        }

        @Bean
        ApprovalsApi toolGateApprovalsApi(ToolGateApprovals approvals, ToolGateReviewerResolver reviewers,
                                          ToolGateRequesterDirectory requesters, ConsoleProperties properties) {
            return new ApprovalsApi(approvals, reviewers, requesters, properties.application());
        }

        @Bean
        ConsoleRedirect toolGateConsoleRedirect() {
            return new ConsoleRedirect();
        }
    }

    /** Spring MVC: the controller, the headers filter and the console's files. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = {SPRING_SECURITY, SERVLET_SECURITY})
    static class Servlet {

        @Bean
        ApprovalsMvcController toolGateApprovalsMvcController(ApprovalsApi api) {
            return new ApprovalsMvcController(api);
        }

        @Bean
        FilterRegistrationBean<ConsoleHeadersFilter> toolGateConsoleHeaders() {
            var registration = new FilterRegistrationBean<>(new ConsoleHeadersFilter());
            registration.addUrlPatterns(ConsoleHeaders.PATH_PREFIX + "*");
            return registration;
        }

        @Bean
        WebMvcConfigurer toolGateConsoleFiles() {
            return new WebMvcConfigurer() {
                @Override
                public void addResourceHandlers(ResourceHandlerRegistry registry) {
                    registry.addResourceHandler(UI_PATH).addResourceLocations(UI_LOCATION);
                }
            };
        }
    }

    /** Spring WebFlux: the controller, the headers filter and the console's files. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = {SPRING_SECURITY, REACTIVE_SECURITY})
    static class Reactive {

        @Bean
        ApprovalsWebFluxController toolGateApprovalsWebFluxController(ApprovalsApi api) {
            return new ApprovalsWebFluxController(api);
        }

        @Bean
        ConsoleHeadersWebFilter toolGateConsoleHeaders() {
            return new ConsoleHeadersWebFilter();
        }

        @Bean
        WebFluxConfigurer toolGateConsoleFiles() {
            return new WebFluxConfigurer() {
                @Override
                public void addResourceHandlers(org.springframework.web.reactive.config.ResourceHandlerRegistry registry) {
                    registry.addResourceHandler(UI_PATH).addResourceLocations(UI_LOCATION);
                }
            };
        }
    }

    /** Turned on in a servlet application without Spring Security's web support: refuse to start. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingClass(SERVLET_SECURITY)
    static class ServletWithoutSecurity {

        @Bean
        Object toolGateConsoleNeedsSpringSecurity() {
            throw new IllegalStateException("toolgate.approvals.ui.enabled=true needs Spring Security"
                    + " (spring-boot-starter-security): a console that decides tool calls must never be open");
        }
    }

    /** Turned on in a reactive application without Spring Security's web support: refuse to start. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnMissingClass(REACTIVE_SECURITY)
    static class ReactiveWithoutSecurity {

        @Bean
        Object toolGateConsoleNeedsSpringSecurity() {
            throw new IllegalStateException("toolgate.approvals.ui.enabled=true needs Spring Security"
                    + " (spring-boot-starter-security): a console that decides tool calls must never be open");
        }
    }
}
