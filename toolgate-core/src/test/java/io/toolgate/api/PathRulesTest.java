package io.toolgate.api;

import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.ArgumentRules.notUnder;
import static io.toolgate.api.policy.ArgumentRules.under;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.policy.InvalidPolicyException;
import io.toolgate.api.policy.ToolGatePolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Path argument rules, from the policy builder to the decision, through the public API only. */
class PathRulesTest {

    @TempDir
    Path base;

    private Path logs;
    private final AtomicInteger callCounter = new AtomicInteger();

    @BeforeEach
    void layOutFolders() throws IOException {
        logs = Files.createDirectories(base.resolve("var/log/app"));
        Files.createDirectories(base.resolve("root/.aws"));
        Files.writeString(logs.resolve("payment.log"), "ok");
        Files.writeString(base.resolve("root/.aws/credentials"), "secret");
    }

    /** Case C, row 3: the log path is steered at the server's cloud credentials. */
    @Test
    void caseCLogReaderCannotLeaveTheLogFolder() {
        ToolGate gate = gate(policy(a -> a.allow("readLog").rule("readLog.path", under(logs.toString()))));

        assertThat(read(gate, logs + "/payment.log")).isInstanceOf(Decision.Allow.class);
        assertThat(read(gate, logs + "/../../../root/.aws/credentials"))
                .isInstanceOfSatisfying(Decision.Deny.class, deny -> {
                    assertThat(deny.ruleId()).isEqualTo("readLog.path");
                    assertThat(deny.reason()).contains("argument 'path'");
                });
    }

    @Test
    void symlinkOutOfTheRootIsDenied() throws IOException {
        Files.createSymbolicLink(logs.resolve("aws"), base.resolve("root/.aws"));
        ToolGate gate = gate(policy(a -> a.allow("readLog").rule("readLog.path", under(logs.toString()))));

        assertThat(read(gate, logs + "/aws/credentials")).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void relativePathResolvesAgainstTheRoot() {
        ToolGate gate = gate(policy(a -> a.allow("readLog").rule("readLog.path", under(logs.toString()))));

        assertThat(read(gate, "payment.log")).isInstanceOf(Decision.Allow.class);
        assertThat(read(gate, "../../../root/.aws/credentials")).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void notUnderCarvesOutAFolder() throws IOException {
        Files.createDirectories(logs.resolve("audit"));
        Files.writeString(logs.resolve("audit/trail.log"), "a");
        ToolGate gate = gate(policy(a -> a.allow("readLog")
                .rule("readLog.path", under(logs.toString()), notUnder(logs.resolve("audit").toString()))));

        assertThat(read(gate, logs + "/audit/trail.log")).isInstanceOf(Decision.Deny.class);
        assertThat(read(gate, logs + "/payment.log")).isInstanceOf(Decision.Allow.class);
    }

    @Test
    void nonStringPathIsDenied() {
        ToolGate gate = gate(policy(a -> a.allow("readLog").rule("readLog.path", under(logs.toString()))));

        assertThat(gate.check(call(ObjectValue.of(Map.of("path", ArgValue.nullValue())))))
                .isInstanceOf(Decision.Deny.class);
    }

    @Test
    void rejectsInvalidPathRules() {
        assertThatThrownBy(() -> policy(a -> a.allow("readLog").rule("readLog.path", under("var/log"))))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> policy(a -> a.allow("readLog").rule("readLog.path", under("/var/log/../../etc"))))
                .isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> policy(a -> a.allow("readLog").rule("readLog.path", under())))
                .isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> policy(a -> a.allow("readLog").rule("readLog.path", under("/a\u0000b"))))
                .isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> policy(a -> a.allow("readLog")
                .rule("readLog.path", under("/var/log"))
                .rule("readLog.path", under("/srv/log"))))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("under twice");
    }

    @Test
    void rejectsUrlAndPathRulesOnOneArgument() {
        assertThatThrownBy(() -> policy(a -> a.allow("readLog")
                .rule("readLog.path", under("/var/log"), hosts("logs.example"))))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("not a mix");
    }

    private static ToolGate gate(ToolGatePolicy policy) {
        return ToolGate.builder(policy).build();
    }

    private static ToolGatePolicy policy(Consumer<ToolGatePolicy.AgentBuilder> agent) {
        return ToolGatePolicy.builder().agent("ops-assistant", agent).build();
    }

    private Decision read(ToolGate gate, String path) {
        return gate.check(call(ObjectValue.of(Map.of("path", ArgValue.text(path)))));
    }

    private ToolCall call(ObjectValue args) {
        return new ToolCall("c" + callCounter.incrementAndGet(), Principal.user("eng", Tenant.none()), "ops-assistant",
                "s1", ToolId.local("readLog"), args, Instant.EPOCH);
    }
}
