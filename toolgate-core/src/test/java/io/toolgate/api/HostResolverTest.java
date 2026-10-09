package io.toolgate.api;

import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.ArgumentRules.schemes;
import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HostResolverTest {

    private final ToolGatePolicy policy = ToolGatePolicy.builder()
            .agent("agent", a -> a.allow("fetch").rule("fetch.url", hosts("*.vendor.example", "docs.example")))
            .build();
    private final List<String> lookups = new ArrayList<>();

    /** DNS points an allowed name at an internal address: the case only a resolver or SafeHttp can catch. */
    @Test
    void deniesAllowedNameThatResolvesToPrivateAddress() {
        ToolGate gate = gateResolving(Map.of("status.vendor.example", List.of("10.0.0.7")));

        assertThat(fetch(gate, "https://status.vendor.example/")).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.reason()).contains("resolves to 10.0.0.7").contains("private network"));
    }

    @Test
    void deniesWhenAnyOfSeveralAddressesIsSpecial() {
        ToolGate gate = gateResolving(Map.of("docs.example", List.of("93.184.216.34", "169.254.169.254")));

        assertThat(fetch(gate, "https://docs.example/")).isInstanceOf(Decision.Deny.class);
    }

    /** The JDK returns ::ffff:a.b.c.d as an IPv4 address, which the IPv4 table then catches. */
    @Test
    void deniesMappedAddressFromTheResolver() {
        ToolGate gate = gateResolving(Map.of("docs.example", List.of("::ffff:169.254.169.254")));

        assertThat(fetch(gate, "https://docs.example/")).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void allowsNameThatResolvesToPublicAddresses() {
        ToolGate gate = gateResolving(Map.of("docs.example", List.of("93.184.216.34", "2606:2800:220:1::1")));

        assertThat(fetch(gate, "https://docs.example/")).isInstanceOf(Decision.Allow.class);
    }

    @Test
    void failsClosedWhenResolutionFails() {
        ToolGate unknown = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).hostResolver(host -> {
            throw new UnknownHostException(host);
        }).build();
        ToolGate broken = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).hostResolver(host -> {
            throw new IllegalStateException("resolver down");
        }).build();
        ToolGate empty = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).hostResolver(host -> List.of()).build();

        assertThat(fetch(unknown, "https://docs.example/")).isInstanceOf(Decision.Deny.class);
        assertThat(fetch(broken, "https://docs.example/")).isInstanceOf(Decision.Deny.class);
        assertThat(fetch(empty, "https://docs.example/")).isInstanceOfSatisfying(Decision.Deny.class,
                deny -> assertThat(deny.reason()).contains("no address"));
    }

    @Test
    void resolvesOnlyNamesThatPassedEveryOtherCheck() {
        ToolGatePolicy anyHost = ToolGatePolicy.builder()
                .agent("agent", a -> a.allow("fetch").rule("fetch.url", schemes("https")))
                .build();
        ToolGate gate = ToolGate.builder(anyHost).toolCatalog(Catalogs.declaring(anyHost)).hostResolver(recordingResolver(Map.of())).build();

        fetch(gate, "https://1.1.1.1/");
        fetch(gate, "https://localhost/");
        fetch(gate, "http://docs.example/");

        assertThat(lookups).isEmpty();
    }

    @Test
    void withoutResolverNamesAreNotResolved() {
        ToolGate gate = ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).build();

        assertThat(fetch(gate, "https://status.vendor.example/")).isInstanceOf(Decision.Allow.class);
    }

    private ToolGate gateResolving(Map<String, List<String>> answers) {
        return ToolGate.builder(policy).toolCatalog(Catalogs.declaring(policy)).hostResolver(recordingResolver(answers)).build();
    }

    private HostResolver recordingResolver(Map<String, List<String>> answers) {
        return host -> {
            lookups.add(host);
            List<InetAddress> addresses = new ArrayList<>();
            for (String literal : answers.getOrDefault(host, List.of())) {
                addresses.add(InetAddress.getByName(literal));   // literals only: never a DNS query
            }
            return addresses;
        };
    }

    private static Decision fetch(ToolGate gate, String url) {
        return gate.check(new ToolCall("c-" + url, Principal.service("svc", Tenant.none()), "agent", "s-" + url,
                ToolId.local("fetch"), ObjectValue.of(Map.of("url", ArgValue.text(url))), Instant.EPOCH));
    }
}
