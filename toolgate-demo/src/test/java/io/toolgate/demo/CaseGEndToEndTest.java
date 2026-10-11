package io.toolgate.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.PolicyReplay;
import io.toolgate.api.Principal;
import io.toolgate.api.ReplayReport;
import io.toolgate.api.Tenant;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.demo.MockModelServer.Call;
import io.toolgate.jdbc.JdbcApprovalQueue;
import io.toolgate.jdbc.JdbcSessionStore;
import io.toolgate.spi.OwnershipResolver;
import io.toolgate.spi.SessionStore;
import io.toolgate.spi.ToolCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Case G end to end: a week of the support chat's emails runs through Spring AI and is recorded in the
 * session store; the team then replays it through the policy it proposes, the demo's own policy with
 * the {@code sendEmail.to} rule switched on, before deploying.
 */
@SpringBootTest(classes = {DemoApplication.class, CaseGEndToEndTest.RecordedSessions.class})
class CaseGEndToEndTest {

    private static final MockModelServer MODEL = start();
    private static final DataSource DATABASE = database();
    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"));
    private static final List<String> DAILY = List.of("ops@mycompany.com", "orders@supplier-a.example",
            "orders@supplier-b.example", "billing@mycompany.com");

    @Autowired
    private SupportChat supportChat;
    @Autowired
    private JdbcSessionStore history;
    @Autowired
    private ToolCatalog catalog;
    @Autowired
    private OwnershipResolver owners;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", MODEL::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test");
    }

    /** The session store keeps the event log replay reads. */
    @TestConfiguration
    static class RecordedSessions {

        @Bean
        JdbcSessionStore sessionStore() {
            return new JdbcSessionStore(DATABASE);
        }
    }

    @AfterAll
    static void stop() {
        MODEL.close();
    }

    @Test
    void theReplayFindsTheSupplierNotificationsThenOnlyTheCustomerReply() {
        Instant start = Instant.now();
        for (int day = 0; day < 7; day++) {
            for (String to : DAILY) {
                MODEL.thenToolCalls(new Call("g-" + day + "-" + to, "sendEmail",
                        "{\"to\":\"" + to + "\",\"body\":\"Daily order summary\"}"));
            }
            MODEL.thenText("Sent the daily summaries.");
            supportChat.ask(MARIA, "day-" + day + "-" + UUID.randomUUID(), "Send today's order summaries");
        }
        MODEL.thenToolCalls(new Call("g-customer", "sendEmail",
                "{\"to\":\"maria@customer.example\",\"body\":\"Your refund is on its way\"}")).thenText("Replied.");
        supportChat.ask(MARIA, "day-6-reply-" + UUID.randomUUID(), "Reply to the customer");
        Instant end = Instant.now().plusSeconds(1);

        ReplayReport strict = replay(proposed("mycompany.com"), start, end);
        ReplayReport withSuppliers = replay(proposed("mycompany.com,supplier-a.example,supplier-b.example"), start, end);

        assertThat(strict.calls()).isEqualTo(29);
        assertThat(strict.changes()).hasSize(15);
        assertThat(strict.summary()).contains("15  local/sendEmail  allow -> deny  rule sendEmail.to");
        assertThat(withSuppliers.changes()).as("only the customer reply still changes, which was the intent")
                .singleElement().satisfies(change -> assertThat(change.sessionId()).startsWith("day-6-reply-"));
    }

    /** The demo's own policy, as it would be deployed with the support chat's email rule switched on. */
    private static ToolGatePolicy proposed(String emailDomains) {
        return new DemoPolicy().toolGatePolicy("enforce", "/var/log/app", "10000", emailDomains, "");
    }

    private ReplayReport replay(ToolGatePolicy policy, Instant from, Instant to) {
        try (Stream<io.toolgate.spi.SessionEvent> events = history.events(from, to)) {
            return PolicyReplay.builder(policy).toolCatalog(catalog).ownershipResolver(owners).build().replay(events);
        }
    }

    private static DataSource database() {
        var h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:case-g-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
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
