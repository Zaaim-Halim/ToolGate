package io.toolgate.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.api.ArgValue;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.jdbc.JdbcApprovalQueue;
import io.toolgate.jdbc.JdbcSessionStore;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import io.toolgate.spi.ValueCodec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** The toolgate command as a user runs it, against a session store in H2. */
class ToolGateCommandTest {

    private static final Principal AGENT = Principal.service("support-chat", Tenant.of("acme"));

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private String jdbcUrl;

    /** A week of the support chat's emails, recorded under the current policy, as Case G has it. */
    @BeforeEach
    void recordAWeek() throws SQLException {
        jdbcUrl = "jdbc:h2:mem:cli-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Connection keep = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = keep.createStatement()) {
            statement.execute(JdbcApprovalQueue.Dialect.H2.ddl());
        }
        var dataSource = new DriverDataSource(jdbcUrl, null, null, getClass().getClassLoader());
        Instant now = Instant.now();
        try (ToolGate live = ToolGate.builder(new Policies.Current().get()).sessionStore(new JdbcSessionStore(dataSource))
                .noDefaultLog().build()) {
            int call = 0;
            for (int day = 0; day < 7; day++) {
                for (String to : List.of("ops@mycompany.com", "orders@supplier-a.example", "orders@supplier-b.example",
                        "billing@mycompany.com")) {
                    live.check(send("day-" + day, "c" + ++call, to, now.minus(Duration.ofDays(6 - day))));
                }
            }
            live.check(send("day-6", "c" + ++call, "maria@customer.example", now));
        }
    }

    @Test
    void replayReportsWhatTheStrictRuleWouldChange() {
        int code = run("replay", "--policy-class", Policies.Strict.class.getName(),
                "--catalog-class", Policies.Catalog.class.getName(), "--jdbc-url", jdbcUrl);

        assertThat(code).isZero();
        assertThat(out.toString()).contains("15  local/sendEmail  allow -> deny  rule sendEmail.to");
    }

    @Test
    void failOnChangeGivesCiAnExitCode() {
        assertThat(run("replay", "--policy-class", Policies.Strict.class.getName(),
                "--catalog-class", Policies.Catalog.class.getName(), "--jdbc-url", jdbcUrl, "--fail-on-change")).isEqualTo(1);
        assertThat(run("replay", "--policy-class", Policies.Current.class.getName(), "--jdbc-url", jdbcUrl,
                "--fail-on-change")).isZero();
    }

    /** A session recorded ten days ago is outside the default week, and inside an eleven-day window. */
    @Test
    void sinceLimitsTheWindow() {
        var dataSource = new DriverDataSource(jdbcUrl, null, null, getClass().getClassLoader());
        Clock tenDaysAgo = Clock.fixed(Instant.now().minus(Duration.ofDays(10)), ZoneOffset.UTC);
        try (ToolGate old = ToolGate.builder(new Policies.Current().get())
                .sessionStore(new JdbcSessionStore(dataSource, ValueCodec.unencrypted(), tenDaysAgo))
                .clock(tenDaysAgo).noDefaultLog().build()) {
            old.check(send("old", "old-1", "attacker@evil.example", tenDaysAgo.instant()));
        }

        run("replay", "--policy-class", Policies.Strict.class.getName(), "--catalog-class", Policies.Catalog.class.getName(),
                "--jdbc-url", jdbcUrl);
        assertThat(out.toString()).contains("15  local/sendEmail").doesNotContain("16  local/sendEmail");

        out.getBuffer().setLength(0);
        run("replay", "--policy-class", Policies.Strict.class.getName(), "--catalog-class", Policies.Catalog.class.getName(),
                "--jdbc-url", jdbcUrl, "--since", "11d");
        assertThat(out.toString()).contains("16  local/sendEmail");
    }

    /** An event log the store sealed with a key is replayed with the same codec, and refused without it. */
    @Test
    void anEncryptedEventLogNeedsItsCodec() throws SQLException {
        String encryptedUrl = "jdbc:h2:mem:cli-sealed-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Connection keep = DriverManager.getConnection(encryptedUrl);
        try (Statement statement = keep.createStatement()) {
            statement.execute(JdbcApprovalQueue.Dialect.H2.ddl());
        }
        var dataSource = new DriverDataSource(encryptedUrl, null, null, getClass().getClassLoader());
        try (ToolGate live = ToolGate.builder(new Policies.Current().get())
                .sessionStore(new JdbcSessionStore(dataSource, new Policies.Codec().get())).noDefaultLog().build()) {
            live.check(send("sealed", "s-1", "attacker@evil.example", Instant.now()));
        }

        assertThat(run("replay", "--policy-class", Policies.Strict.class.getName(), "--catalog-class",
                Policies.Catalog.class.getName(), "--jdbc-url", encryptedUrl, "--codec-class", Policies.Codec.class.getName()))
                .isZero();
        assertThat(out.toString()).contains("1  local/sendEmail  allow -> deny");
        assertThat(run("replay", "--policy-class", Policies.Strict.class.getName(), "--catalog-class",
                Policies.Catalog.class.getName(), "--jdbc-url", encryptedUrl)).isEqualTo(2);
    }

    @Test
    void aRuleWithoutTheCatalogFailsClearly() {
        assertThat(run("replay", "--policy-class", Policies.Strict.class.getName(), "--jdbc-url", jdbcUrl)).isEqualTo(2);
        assertThat(err.toString()).contains("replay failed");
    }

    @Test
    void validatePrintsTheHashOrWhy() {
        assertThat(run("validate", "--policy-class", Policies.Strict.class.getName())).isZero();
        assertThat(out.toString()).contains("valid: ").contains("hash ");

        assertThat(run("validate", "--policy-class", Policies.Broken.class.getName())).isEqualTo(2);
        assertThat(run("validate", "--policy-class", Policies.NotASupplier.class.getName())).isEqualTo(2);
        assertThat(run("validate", "--policy-class", "com.example.Missing")).isEqualTo(2);
        assertThat(err.toString()).contains("Supplier").contains("com.example.Missing");
    }

    @Test
    void durations() {
        assertThat(History.duration("7d")).isEqualTo(Duration.ofDays(7));
        assertThat(History.duration("12h")).isEqualTo(Duration.ofHours(12));
        assertThat(History.duration("P2D")).isEqualTo(Duration.ofDays(2));
        assertThatIllegalArgumentException().isThrownBy(() -> History.duration("7"));
        assertThatIllegalArgumentException().isThrownBy(() -> History.duration("0d"));
        assertThatIllegalArgumentException().isThrownBy(() -> History.duration("d"));
    }

    private int run(String... args) {
        return new CommandLine(new ToolGateCommand()).setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true))
                .execute(args);
    }

    private static ToolCall send(String session, String callId, String to, Instant at) {
        return new ToolCall(callId, AGENT, "support-chat", session, ToolId.local("sendEmail"),
                ObjectValue.of(Map.of("to", ArgValue.text(to))), at);
    }
}
