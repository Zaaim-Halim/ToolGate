package io.toolgate.cli;

import static io.toolgate.email.EmailRules.emailDomains;
import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.Mode;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.PolicyReplay;
import io.toolgate.api.Principal;
import io.toolgate.api.ReplayReport;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolDefinition;
import io.toolgate.api.ToolGate;
import io.toolgate.api.ToolId;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.jdbc.JdbcApprovalQueue;
import io.toolgate.jdbc.JdbcSessionStore;
import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.ToolCatalog;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code toolgate suggest} end to end: a week recorded in monitor mode on a policy that lists only
 * {@code sendEmail}, with its recipient rule, then the draft compiled and the same week replayed through it.
 */
class SuggestCommandTest {

    private static final Principal AGENT = Principal.service("support-chat", Tenant.of("acme"));
    private static final ToolCatalog.Mutable CATALOG = ToolCatalog.inMemory()
            .put(ToolDefinition.of(ToolId.local("fetchPage"), "Fetch a page", schema("url", "string")))
            .put(ToolDefinition.of(ToolId.local("sendEmail"), "Send an email", schema("to", "string")))
            .put(ToolDefinition.of(ToolId.local("issueRefund"), "Refund", schema("amount", "number")))
            .put(ToolDefinition.of(ToolId.local("deleteAllOrders"), "Delete all orders",
                    "{\"type\":\"object\",\"properties\":{}}"));

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private String jdbcUrl;
    private JdbcSessionStore store;
    private int call;
    private String firstFetch;
    private String usualRefund;
    private String injectedRefund;
    private String injectedHost;
    private String injectedTool;
    private final List<String> injectedRecipients = new ArrayList<>();

    @TempDir
    Path folder;

    @BeforeEach
    void recordAWeekInMonitorMode() throws SQLException {
        jdbcUrl = "jdbc:h2:mem:suggest-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Connection keep = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = keep.createStatement()) {
            statement.execute(JdbcApprovalQueue.Dialect.H2.ddl());
        }
        store = new JdbcSessionStore(new DriverDataSource(jdbcUrl, null, null, getClass().getClassLoader()));
        ToolGatePolicy recording = ToolGatePolicy.builder().mode(Mode.MONITOR)
                .agent("support-chat", a -> a.allow("sendEmail").rule("sendEmail.to", emailDomains("mycompany.com")))
                .build();
        Instant start = Instant.now().minus(Duration.ofDays(6));
        try (ToolGate live = ToolGate.builder(recording).sessionStore(store).toolCatalog(CATALOG).noDefaultLog().build()) {
            for (int day = 0; day < 6; day++) {
                String session = "day-" + day;
                Instant at = start.plus(Duration.ofDays(day));
                String fetch = check(live, call(session, "fetchPage", "url", ArgValue.text("https://docs.example/guide/" + day), at));
                firstFetch = firstFetch == null ? fetch : firstFetch;
                check(live, call(session, "sendEmail", "to", ArgValue.text("ops@mycompany.com"), at));
                String refund = check(live, call(session, "issueRefund", "amount", ArgValue.number(10L * (day + 1)), at));
                usualRefund = day == 1 ? refund : usualRefund;
            }
            // An injected recipient, blocked by the recorded rule in three sessions: it must stay blocked.
            for (int day = 0; day < 3; day++) {
                injectedRecipients.add(check(live, call("day-" + day, "sendEmail", "to",
                        ArgValue.text("remit@acme-payments.example"), start)));
            }
            // An injected host, tool and amount, each in one session only.
            injectedHost = check(live, call("day-5", "fetchPage", "url", ArgValue.text("https://evil.example/collect"), start));
            injectedTool = check(live, call("day-5", "deleteAllOrders", null, null, start));
            injectedRefund = check(live, call("day-5", "issueRefund", "amount", ArgValue.number(5000), start));
        }
    }

    @Test
    void theDraftAllowsTheWeekAndKeepsTheAttacksOut() throws Exception {
        Path source = folder.resolve("SuggestedPolicy.java");

        int code = run("suggest", "--jdbc-url", jdbcUrl, "-o", source.toString());

        assertThat(code).as(err.toString()).isZero();
        String java = Files.readString(source);
        assertThat(java)
                .contains(".allow(\"fetchPage\", \"issueRefund\", \"sendEmail\")")
                .contains(".rule(\"fetchPage.url\", schemes(\"https\"), hosts(\"docs.example\"))")
                .contains(".rule(\"sendEmail.to\", emailDomains(\"mycompany.com\"))")
                .contains("issueRefund.amount: 7 values, largest 5000; limit 40, the largest seen in 3 sessions; larger ones wait"
                        + " for review")
                .contains(".rule(\"issueRefund.amount\", atLeast(\"0\"), atMost(\"40\").otherwise(OnViolation.DEFER))")
                .contains("//   agent support-chat, tool deleteAllOrders: 1 call in 1 session")
                .contains("//   agent support-chat, fetchPage.url host evil.example: 1 call in 1 session")
                .doesNotContain("acme-payments", "ops@", "/guide/");
        assertThat(err.toString()).contains("Drafted from 21 of 24 tool calls in 6 sessions")
                .contains("Left out, refused by the recorded policy: 3 (sendEmail.to: 3)");

        ToolGatePolicy draft = compile(source);
        Map<String, Decision> after = replay(draft).changes().stream()
                .collect(Collectors.toMap(ReplayReport.Change::callId, ReplayReport.Change::after));

        // Not listed under the recorded policy, allowed under the draft.
        assertThat(after.get(firstFetch)).isInstanceOf(Decision.Allow.class);
        assertThat(after.get(usualRefund)).isInstanceOf(Decision.Allow.class);
        // The injected amount, seen in one session, did not raise the limit: it waits for review.
        assertThat(after.get(injectedRefund)).isInstanceOfSatisfying(Decision.Defer.class,
                defer -> assertThat(defer.ruleId()).isEqualTo("issueRefund.amount"));
        // The rare host is denied; the rare tool stays off the allow-list, so its decision does not change.
        assertThat(after.get(injectedHost)).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.ruleId()).isEqualTo("fetchPage.url"));
        assertThat(after).doesNotContainKey(injectedTool);
        // The blocked recipient stays denied by the same rule, so its decision does not change.
        assertThat(after).doesNotContainKeys(injectedRecipients.toArray(String[]::new));
    }

    @Test
    void rareToolsNeedFewerSessionsWhenAskedTo() throws Exception {
        Path source = folder.resolve("SuggestedPolicy.java");

        assertThat(run("suggest", "--jdbc-url", jdbcUrl, "--min-sessions", "1", "--class-name", "com.acme.Draft",
                "-o", source.toString())).isZero();

        assertThat(Files.readString(source)).startsWith("package com.acme;")
                .contains("public final class Draft implements Supplier<ToolGatePolicy>")
                .contains("\"deleteAllOrders\"")
                .contains("hosts(\"docs.example\", \"evil.example\")");
    }

    @Test
    void anEmptyWindowSaysSo() {
        assertThat(run("suggest", "--jdbc-url", "jdbc:h2:mem:empty-" + UUID.randomUUID() + ";INIT=" + ddlInit())).isEqualTo(1);
        assertThat(err.toString()).contains("no tool calls were recorded");
    }

    /** Names come from history, which a model can steer: none may end a literal or a comment early. */
    @Test
    void namesFromHistoryStayInsideTheirLiterals() throws Exception {
        String agent = "x\"); System.exit(3); //\n*/ \\u000a \u2028";
        ToolGatePolicy recording = ToolGatePolicy.builder().mode(Mode.MONITOR).agent("support-chat", a -> a.allow("sendEmail")).build();
        var hostile = new ArrayList<String>();
        try (ToolGate live = ToolGate.builder(recording).sessionStore(store).toolCatalog(CATALOG).noDefaultLog().build()) {
            hostile.add(check(live, new ToolCall("h1", AGENT, agent, "hostile", ToolId.local("deleteAllOrders"),
                    ObjectValue.of(Map.of()), Instant.now())));
        }
        Path source = folder.resolve("SuggestedPolicy.java");

        assertThat(run("suggest", "--jdbc-url", jdbcUrl, "--min-sessions", "1", "-o", source.toString())).isZero();

        Map<String, Decision> after = replay(compile(source)).changes().stream()
                .collect(Collectors.toMap(ReplayReport.Change::callId, ReplayReport.Change::after));
        assertThat(after.get(hostile.getFirst())).as("the agent's name survives exactly").isInstanceOf(Decision.Allow.class);
    }

    private ReplayReport replay(ToolGatePolicy draft) {
        try (Stream<SessionEvent> history = store.events(Instant.now().minus(Duration.ofDays(8)), Instant.now())) {
            return PolicyReplay.builder(draft).toolCatalog(CATALOG).build().replay(history);
        }
    }

    /** Compiles the draft against the test class path, as an application would, and builds its policy. */
    private ToolGatePolicy compile(Path source) throws Exception {
        String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path classes = Files.createDirectories(folder.resolve("classes"));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", classPath, "-d", classes.toString(), source.toString());
        assertThat(status).as("the draft compiles").isZero();
        try (var loader = new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            @SuppressWarnings("unchecked")
            var supplier = (Supplier<ToolGatePolicy>) loader.loadClass("SuggestedPolicy").getConstructor().newInstance();
            return supplier.get();
        }
    }

    private static String check(ToolGate live, ToolCall toolCall) {
        live.check(toolCall);
        return toolCall.callId();
    }

    private ToolCall call(String session, String tool, String argument, ArgValue value, Instant at) {
        ObjectValue args = argument == null ? ObjectValue.of(Map.of()) : ObjectValue.of(Map.of(argument, value));
        return new ToolCall("c" + ++call, AGENT, "support-chat", session, ToolId.local(tool), args, at);
    }

    private static String schema(String argument, String type) {
        return "{\"type\":\"object\",\"properties\":{\"" + argument + "\":{\"type\":\"" + type + "\"}}}";
    }

    private static String ddlInit() {
        return "RUNSCRIPT FROM 'classpath:/io/toolgate/jdbc/h2.sql'";
    }

    private int run(String... args) {
        return new CommandLine(new ToolGateCommand()).setOut(new PrintWriter(out, true)).setErr(new PrintWriter(err, true))
                .execute(args);
    }
}
