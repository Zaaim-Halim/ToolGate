package io.toolgate.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.Approval;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.Decision;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.demo.MockModelServer.Call;
import io.toolgate.jdbc.JdbcApprovalQueue;
import io.toolgate.jdbc.JdbcSessionStore;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.SessionStore;
import io.toolgate.springai.ToolGateApprovals;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Case F end to end: a refund over 200 is deferred under one policy, finance redeploys with a lower cap,
 * and the approval is re-checked against the policy now running. Two application contexts stand for
 * the deployments before and after; they share the approval queue and session store in one database.
 */
class CaseFEndToEndTest {

    private static final MockModelServer MODEL = start();
    private static final Principal AGENT = Principal.service("refund-agent", Tenant.of("acme"));
    private static final Principal LENA = Principal.user("lena", Tenant.of("acme"), "finance");
    private static final DataSource DATABASE = database();

    /** The application's stores, in its database, as a deployment with more than one instance has them. */
    @Configuration(proxyBeanMethods = false)
    static class SharedStores {

        @Bean
        ApprovalQueue approvalQueue() {
            return new JdbcApprovalQueue(DATABASE);
        }

        @Bean
        SessionStore sessionStore() {
            return new JdbcSessionStore(DATABASE);
        }
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @Test
    void theReleaseIsRecheckedUnderTheNewCapAndRefusedOnce() {
        RefundTools.clear();
        MODEL.reset();
        String approvalId;
        try (ConfigurableApplicationContext before = deploy("10000")) {
            MODEL.thenToolCalls(new Call("f1", "issueRefund", "{\"order\":\"7731\",\"amount\":480}"))
                    .thenText("Your refund is pending review.");

            assertThat(before.getBean(RefundJob.class).handle("Please refund order 7731, 480 euros."))
                    .isEqualTo("Your refund is pending review.");
            assertThat(MODEL.requests().get(1)).as("10:02, the agent is told the refund is pending")
                    .contains(Decision.Defer.MODEL_MESSAGE);
            approvalId = before.getBean(ToolGateApprovals.class).pending().getFirst().approvalId();
        }

        try (ConfigurableApplicationContext after = deploy("300")) {
            ToolGateApprovals approvals = after.getBean(ToolGateApprovals.class);

            assertThat(approvals.approve(approvalId, LENA, AGENT)).as("15:10, re-checked against the new cap")
                    .isInstanceOfSatisfying(Approval.Refused.class,
                            refused -> assertThat(refused.reason()).contains("480").contains("300"));
            assertThat(approvals.approve(approvalId, LENA, AGENT)).as("15:11, the second click")
                    .isEqualTo(new Approval.AlreadyDecided(ApprovalStatus.REFUSED));
        }
        assertThat(RefundTools.issued()).as("no refund was issued").isEmpty();
    }

    /**
     * The same flow with the cap unchanged: an instance started after the deferral releases the refund
     * and runs it once. Regression: the new instance's catalog had no definition for the tool yet, so the
     * re-check refused every approved call with argument rules after a restart.
     */
    @Test
    void anInstanceStartedAfterTheDeferralRunsTheApprovedRefundOnce() {
        RefundTools.clear();
        MODEL.reset();
        String approvalId;
        try (ConfigurableApplicationContext before = deploy("10000")) {
            MODEL.thenToolCalls(new Call("f1", "issueRefund", "{\"order\":\"7731\",\"amount\":480}"))
                    .thenText("Your refund is pending review.");
            before.getBean(RefundJob.class).handle("Please refund order 7731, 480 euros.");
            approvalId = before.getBean(ToolGateApprovals.class).pending().getFirst().approvalId();
        }

        try (ConfigurableApplicationContext restarted = deploy("10000")) {
            ToolGateApprovals approvals = restarted.getBean(ToolGateApprovals.class);

            assertThat(approvals.approve(approvalId, LENA, AGENT)).isInstanceOf(Approval.Released.class);
            assertThat(approvals.approve(approvalId, LENA, AGENT))
                    .isEqualTo(new Approval.AlreadyDecided(ApprovalStatus.EXECUTED));
        }
        assertThat(RefundTools.issued()).containsExactly("7731:480");
    }

    /** Starts the demo with finance's cap on automated refunds, as one deployment of the policy. */
    private static ConfigurableApplicationContext deploy(String cap) {
        return new SpringApplicationBuilder(DemoApplication.class, SharedStores.class)
                .web(WebApplicationType.NONE)
                .properties("spring.ai.openai.base-url=" + MODEL.baseUrl(), "spring.ai.openai.api-key=test",
                        "demo.refunds.cap=" + cap)
                .run();
    }

    private static DataSource database() {
        var h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:case-f-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(JdbcApprovalQueue.Dialect.H2.ddl());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return h2;
    }

    private static MockModelServer start() {
        try {
            return new MockModelServer();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
