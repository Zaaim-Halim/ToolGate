package io.toolgate.api;

import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.ArgumentRules.ports;
import static io.toolgate.api.policy.ArgumentRules.schemes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.toolgate.api.policy.InvalidPolicyException;
import io.toolgate.api.policy.ToolArgument;
import io.toolgate.api.policy.ToolGatePolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** URL argument rules, from the policy builder to the decision, through the public API only. */
class ArgumentRulesTest {

    private final AtomicInteger callCounter = new AtomicInteger();

    /** Case C: the ops assistant is pointed at the cloud metadata service. */
    @Test
    void caseCMetadataFetchIsDeniedAndStatusPageAllowed() {
        ToolGate gate = gate(ToolGatePolicy.builder()
                .agent("ops-assistant", a -> a
                        .allow("readLog", "fetch")
                        .rule("fetch.url", hosts("status.vendor.example"), schemes("https")))
                .build());

        assertThat(check(gate, "ops-assistant", "fetch", "url",
                "http://169.254.169.254/latest/meta-data/iam/security-credentials/"))
                .isInstanceOfSatisfying(Decision.Deny.class, deny -> {
                    assertThat(deny.ruleId()).isEqualTo("fetch.url");
                    assertThat(deny.reason()).contains("argument 'url'");
                });
        assertThat(check(gate, "ops-assistant", "fetch", "url", "https://status.vendor.example/api/v2/status"))
                .isEqualTo(new Decision.Allow("allow-list"));
    }

    @Test
    void specialAddressesAreDeniedEvenWithoutHostPatterns() {
        ToolGate gate = gate(policy(a -> a.allow("fetch").rule("fetch.url", schemes("https", "http"))));

        assertThat(check(gate, "agent", "fetch", "url", "http://[::ffff:169.254.169.254]/"))
                .isInstanceOf(Decision.Deny.class);
        assertThat(check(gate, "agent", "fetch", "url", "https://public.example/"))
                .isInstanceOf(Decision.Allow.class);
    }

    @Test
    void absentArgumentPassesButWrongTypeIsDenied() {
        ToolGate gate = gate(policy(a -> a.allow("fetch").rule("fetch.url", hosts("docs.example"))));

        assertThat(gate.check(call("agent", "fetch", ObjectValue.empty()))).isInstanceOf(Decision.Allow.class);
        for (ArgValue wrongType : new ArgValue[] {
                ArgValue.nullValue(), ArgValue.number(1), ArgValue.bool(true),
                ArgValue.list(List.of(ArgValue.text("https://docs.example/"))),
                ArgValue.object(Map.of("href", ArgValue.text("https://docs.example/")))}) {
            assertThat(gate.check(call("agent", "fetch", ObjectValue.of(Map.of("url", wrongType)))))
                    .as("url = %s", wrongType)
                    .isInstanceOfSatisfying(Decision.Deny.class,
                            deny -> assertThat(deny.reason()).contains("must be a string"));
        }
    }

    @Test
    void rulesApplyOnlyToTheirOwnAgentAndTool() {
        ToolGate gate = gate(ToolGatePolicy.builder()
                .agent("strict", a -> a.allow("fetch", "download").rule("fetch.url", hosts("docs.example")))
                .agent("lenient", a -> a.allow("fetch"))
                .build());

        assertThat(check(gate, "strict", "fetch", "url", "https://other.example/")).isInstanceOf(Decision.Deny.class);
        assertThat(check(gate, "strict", "download", "url", "https://other.example/")).isInstanceOf(Decision.Allow.class);
        assertThat(check(gate, "lenient", "fetch", "url", "https://other.example/")).isInstanceOf(Decision.Allow.class);
    }

    @Test
    void remoteToolArgumentsCanBeRuled() {
        ToolGate gate = gate(ToolGatePolicy.builder()
                .mcpServers("web")
                .agent("agent", a -> a.allow("mcp:web/fetch").rule("mcp:web/fetch.url", hosts("docs.example")))
                .build());

        assertThat(gate.check(new ToolCall("c1", Principal.service("svc", Tenant.none()), "agent", "s1",
                ToolId.mcp("web", "fetch"), ObjectValue.of(Map.of("url", ArgValue.text("https://evil.example/"))),
                Instant.EPOCH)))
                .isInstanceOfSatisfying(Decision.Deny.class,
                        deny -> assertThat(deny.ruleId()).isEqualTo("mcp:web/fetch.url"));
    }

    @Test
    void portsRuleAllowsListedPortsOnly() {
        ToolGate gate = gate(policy(a -> a.allow("fetch").rule("fetch.url", ports(8443))));

        assertThat(check(gate, "agent", "fetch", "url", "https://x.example:8443/")).isInstanceOf(Decision.Allow.class);
        assertThat(check(gate, "agent", "fetch", "url", "https://x.example/")).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void monitorModeRecordsButDoesNotBlock() {
        ToolGate gate = gate(ToolGatePolicy.builder()
                .mode(Mode.MONITOR)
                .agent("agent", a -> a.allow("fetch").rule("fetch.url", hosts("docs.example")))
                .build());

        assertThat(check(gate, "agent", "fetch", "url", "http://169.254.169.254/"))
                .isEqualTo(new Decision.Allow("mode.monitor"));
    }

    @Test
    void targetIsSplitAtTheLastDot() {
        assertThat(ToolArgument.parse("fetchSupplierPage.url"))
                .isEqualTo(new ToolArgument(ToolId.local("fetchSupplierPage"), "url"));
        assertThat(ToolArgument.parse("mcp:web/page.fetch.url"))
                .isEqualTo(new ToolArgument(ToolId.mcp("web", "page.fetch"), "url"));
        assertThat(ToolArgument.parse("local/fetch.url").ruleId()).isEqualTo("fetch.url");
    }

    @ParameterizedTest
    @ValueSource(strings = {"url", ".url", "fetch.", "mcp:web.url", ""})
    void rejectsMalformedTargets(String target) {
        assertThatThrownBy(() -> ToolGatePolicy.builder().agent("a", a -> a.allow("fetch").rule(target, hosts("x.example"))))
                .isInstanceOf(InvalidPolicyException.class);
    }

    @Test
    void rejectsRuleOnToolTheAgentCannotCall() {
        assertThatThrownBy(() -> policy(a -> a.allow("fetch").rule("download.url", hosts("x.example"))))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("not allowed to call");
    }

    @Test
    void rejectsTheSameKindTwiceForOneArgument() {
        assertThatThrownBy(() -> policy(a -> a.allow("fetch")
                .rule("fetch.url", hosts("a.example"))
                .rule("fetch.url", hosts("b.example"))))
                .isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("hosts twice");
    }

    @Test
    void differentKindsAddUp() {
        ToolGate gate = gate(policy(a -> a.allow("fetch")
                .rule("fetch.url", hosts("docs.example"))
                .rule("fetch.url", schemes("https"))));

        assertThat(check(gate, "agent", "fetch", "url", "http://docs.example/")).isInstanceOf(Decision.Deny.class);
        assertThat(check(gate, "agent", "fetch", "url", "https://docs.example/")).isInstanceOf(Decision.Allow.class);
    }

    @Test
    void rejectsInvalidRuleValues() {
        assertThatThrownBy(() -> policy(a -> a.allow("fetch").rule("fetch.url")))
                .isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> policy(a -> a.allow("fetch").rule("fetch.url", hosts())))
                .isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> policy(a -> a.allow("fetch").rule("fetch.url", hosts("10.0.0.1"))))
                .isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> policy(a -> a.allow("fetch").rule("fetch.url", hosts("a.*.example"))))
                .isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> policy(a -> a.allow("fetch").rule("fetch.url", schemes("ftp"))))
                .isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> policy(a -> a.allow("fetch").rule("fetch.url", ports(0))))
                .isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> policy(a -> a.allow("fetch").rule("fetch.url", ports(65_536))))
                .isInstanceOf(InvalidPolicyException.class);
    }

    @Test
    void schemesAndHostsIgnoreCase() {
        ToolGate gate = gate(policy(a -> a.allow("fetch").rule("fetch.url", hosts("Docs.Example"), schemes("HTTPS"))));

        assertThat(check(gate, "agent", "fetch", "url", "https://docs.example/")).isInstanceOf(Decision.Allow.class);
    }

    private static ToolGate gate(ToolGatePolicy policy) {
        return ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).build();
    }

    private static ToolGatePolicy policy(Consumer<ToolGatePolicy.AgentBuilder> agent) {
        return ToolGatePolicy.builder().agent("agent", agent).build();
    }

    private Decision check(ToolGate gate, String agentId, String tool, String argument, String value) {
        return gate.check(call(agentId, tool, ObjectValue.of(Map.of(argument, ArgValue.text(value)))));
    }

    private ToolCall call(String agentId, String tool, ObjectValue args) {
        return new ToolCall("c" + callCounter.incrementAndGet(), Principal.service("svc", Tenant.none()), agentId,
                "s1", ToolId.local(tool), args, Instant.EPOCH);
    }
}
