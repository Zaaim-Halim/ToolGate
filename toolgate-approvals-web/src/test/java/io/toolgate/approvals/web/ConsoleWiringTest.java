package io.toolgate.approvals.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.springai.ToolGateAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.server.SecurityWebFilterChain;

/** Nothing exists until the application turns the console on, and it never runs without Spring Security. */
class ConsoleWiringTest {

    private final WebApplicationContextRunner servlet = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolGateAutoConfiguration.class, ToolGateApprovalsWebAutoConfiguration.class))
            .withBean(ToolGatePolicy.class, () -> ToolGatePolicy.builder().agent("a", agent -> agent.allow("t")).build());

    @Test
    void addingTheModuleExposesNothingUntilItIsTurnedOn() {
        servlet.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(ApprovalsApi.class)
                .doesNotHaveBean(ApprovalsMvcController.class)
                .doesNotHaveBean(ConsoleRedirect.class));
    }

    @Test
    void turnedOnItHasTheApiAndTheDefaultReviewer() {
        servlet.withPropertyValues("toolgate.approvals.ui.enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ApprovalsMvcController.class)
                    .doesNotHaveBean(ApprovalsWebFluxController.class);
            assertThat(context.getBean(ToolGateReviewerResolver.class)).isInstanceOf(AuthorityReviewerResolver.class);
        });
    }

    @Test
    void turnedOnWithoutSpringSecurityAServletApplicationDoesNotStart() {
        servlet.withPropertyValues("toolgate.approvals.ui.enabled=true")
                .withClassLoader(new FilteredClassLoader(SecurityFilterChain.class))
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("needs Spring Security"));
    }

    @Test
    void turnedOnWithoutSpringSecurityAReactiveApplicationDoesNotStart() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolGateAutoConfiguration.class, ToolGateApprovalsWebAutoConfiguration.class))
                .withBean(ToolGatePolicy.class, () -> ToolGatePolicy.builder().agent("a", agent -> agent.allow("t")).build())
                .withPropertyValues("toolgate.approvals.ui.enabled=true")
                .withClassLoader(new FilteredClassLoader(SecurityWebFilterChain.class))
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("needs Spring Security"));
    }
}
