package io.toolgate.api;

import static io.toolgate.api.policy.ArgumentRules.hosts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.ToolCatalog;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeclaredArgumentsTest {

    private static final ToolId FETCH = ToolId.local("fetch");
    private static final ToolId SEARCH = ToolId.local("search");

    private final ToolGatePolicy policy = ToolGatePolicy.builder()
            .agent("ops", a -> a.allow("fetch", "search").rule("fetch.url", hosts("status.example")))
            .agent("other", a -> a.allow("fetch"))
            .build();
    private final ToolCatalog.Mutable catalog = ToolCatalog.inMemory()
            .put(Catalogs.definition(FETCH, Set.of("url", "timeout")));
    private final ToolGate gate = ToolGate.builder(policy).toolCatalog(catalog).noDefaultLog().build();
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void declaredArgumentsPass() {
        assertThat(check("ops", FETCH, Map.of("url", "https://status.example/", "timeout", "5")))
                .isInstanceOf(Decision.Allow.class);
    }

    @Test
    void anExtraArgumentBesideARuledOneIsDenied() {
        assertThat(check("ops", FETCH, Map.of("url", "https://status.example/", "proxy", "http://evil.example")))
                .isInstanceOfSatisfying(Decision.Deny.class, deny -> {
                    assertThat(deny.ruleId()).isEqualTo(BuiltInRules.ARGUMENTS_DECLARED);
                    assertThat(deny.reason()).contains("'proxy'").doesNotContain("'url'");
                });
    }

    @Test
    void aRuleOnAnArgumentTheToolDoesNotDeclareDenies() {
        catalog.put(Catalogs.definition(FETCH, Set.of("address")));

        assertThat(check("ops", FETCH, Map.of("address", "http://169.254.169.254/")))
                .isInstanceOfSatisfying(Decision.Deny.class, deny -> {
                    assertThat(deny.ruleId()).isEqualTo(BuiltInRules.ARGUMENTS_DECLARED);
                    assertThat(deny.reason()).contains("rules on 'url'");
                });
    }

    @Test
    void theArgumentsDeclaredByTheOfferedDefinitionAreTheOnesThatCount() {
        catalog.put(Catalogs.definition(FETCH, Set.of("url")));

        assertThat(gate.check(call("ops", FETCH, Map.of("url", "https://status.example/", "timeout", "5")),
                Catalogs.definition(FETCH, Set.of("url", "timeout")))).isInstanceOf(Decision.Allow.class);
    }

    @Test
    void aToolWithRulesTheCatalogDoesNotKnowIsDenied() {
        catalog.remove(FETCH);

        assertThat(check("ops", FETCH, Map.of("url", "https://status.example/")))
                .isInstanceOfSatisfying(Decision.Deny.class, deny -> assertThat(deny.reason()).contains("no definition"));
    }

    @Test
    void aSchemaWithoutPropertiesDeclaresNothing() {
        catalog.put(new ToolDefinition(FETCH, "", ArgValue.object(Map.of("type", ArgValue.text("object")))));

        assertThat(check("ops", FETCH, Map.of())).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.reason()).contains("rules on 'url'"));
    }

    @Test
    void toolsAndAgentsWithoutRulesAreNotChecked() {
        assertThat(check("ops", SEARCH, Map.of("anything", "x"))).isInstanceOf(Decision.Allow.class);
        assertThat(check("other", FETCH, Map.of("proxy", "x"))).isInstanceOf(Decision.Allow.class);
    }

    @Test
    void manyNamesAreQuotedOnlyInPart() {
        var args = new LinkedHashMap<String, String>();
        for (int index = 0; index < 8; index++) {
            args.put("extra" + index, "x");
        }

        assertThat(check("ops", FETCH, args)).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.reason()).contains("and 3 more").doesNotContain("extra7"));
    }

    @Test
    void canBeMonitoredFirst() {
        ToolGatePolicy monitored = ToolGatePolicy.builder()
                .ruleMode(BuiltInRules.ARGUMENTS_DECLARED, Mode.MONITOR)
                .agent("ops", a -> a.allow("fetch").rule("fetch.url", hosts("status.example")))
                .build();
        ToolGate monitoring = ToolGate.builder(monitored).toolCatalog(catalog).noDefaultLog().build();

        assertThat(monitoring.check(call("ops", FETCH, Map.of("url", "https://status.example/", "proxy", "x"))))
                .isInstanceOf(Decision.Allow.class);
    }

    @Test
    void argumentRulesNeedACatalog() {
        assertThatIllegalStateException().isThrownBy(() -> ToolGate.builder(policy).build())
                .withMessageContaining("toolCatalog");
        assertThatIllegalStateException().isThrownBy(() -> PolicyReplay.builder(policy).build())
                .withMessageContaining("toolCatalog");
    }

    private Decision check(String agent, ToolId tool, Map<String, String> args) {
        return gate.check(call(agent, tool, args));
    }

    private ToolCall call(String agent, ToolId tool, Map<String, String> args) {
        var values = new LinkedHashMap<String, ArgValue>();
        args.forEach((name, value) -> values.put(name, ArgValue.text(value)));
        return new ToolCall("c" + calls.incrementAndGet(), Principal.service(agent, Tenant.of("acme")), agent,
                "s-" + agent + calls.get(), tool, ObjectValue.of(values), Instant.EPOCH);
    }
}
