package io.toolgate.safehttp;

import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.ArgumentRules.ports;
import static io.toolgate.api.policy.ArgumentRules.schemes;
import static io.toolgate.api.policy.ArgumentRules.under;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.toolgate.api.Destinations;
import io.toolgate.spi.HostResolver;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SafeHttp against a server on 127.0.0.1. Loopback is special-purpose, so these tests let it through
 * the address check; every other special-purpose range stays refused, as in production.
 */
class SafeHttpTest {

    private final List<String> served = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private int port;

    /** Names as a DNS the attacker partly controls might answer them. */
    private final Map<String, List<String>> dns = Map.of(
            "status.vendor.example", List.of("127.0.0.1"),
            "metadata.vendor.example", List.of("169.254.169.254"),
            "rebind.vendor.example", List.of("127.0.0.1", "10.0.0.7"));

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            served.add(exchange.getRequestURI().getPath());
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/redirect")) {
                exchange.getResponseHeaders().set("Location", exchange.getRequestURI().getQuery());
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            byte[] body = ("ok " + path).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void anAllowedHostIsFetched() throws IOException {
        try (CloseableHttpClient http = client()) {
            assertThat(get(http, "http://status.vendor.example:" + port + "/api/v2/status")).isEqualTo("200 ok /api/v2/status");
        }
    }

    @Test
    void aHostOffTheRulesIsRefusedBeforeAnythingIsSent() throws IOException {
        try (CloseableHttpClient http = client()) {
            assertThatThrownBy(() -> get(http, "http://evil.example:" + port + "/"))
                    .isInstanceOf(RefusedDestinationException.class).hasMessageContaining("evil.example");
        }
        assertThat(served).isEmpty();
    }

    /** The rule allows the name; the name points at the metadata service. */
    @Test
    void aNameResolvingToAMetadataAddressIsRefused() throws IOException {
        try (CloseableHttpClient http = client()) {
            assertThatThrownBy(() -> get(http, "http://metadata.vendor.example:" + port + "/"))
                    .isInstanceOf(RefusedDestinationException.class).hasMessageContaining("169.254.169.254");
        }
        assertThat(served).isEmpty();
    }

    /** One special-purpose address among several refuses the name: the client might pick any of them. */
    @Test
    void aNameWithOneBadAddressIsRefused() throws IOException {
        try (CloseableHttpClient http = client()) {
            assertThatThrownBy(() -> get(http, "http://rebind.vendor.example:" + port + "/"))
                    .isInstanceOf(RefusedDestinationException.class).hasMessageContaining("10.0.0.7");
        }
        assertThat(served).isEmpty();
    }

    @Test
    void everyRedirectIsCheckedLikeTheFirstRequest() throws IOException {
        try (CloseableHttpClient http = client()) {
            assertThat(get(http, "http://status.vendor.example:" + port + "/redirect?/landed")).isEqualTo("200 ok /landed");
            assertThatThrownBy(() -> get(http, "http://status.vendor.example:" + port
                    + "/redirect?http://169.254.169.254/latest/meta-data/"))
                    .isInstanceOf(RefusedDestinationException.class).hasMessageContaining("169.254.169.254");
            assertThatThrownBy(() -> get(http, "http://status.vendor.example:" + port
                    + "/redirect?http://evil.example:" + port + "/"))
                    .isInstanceOf(RefusedDestinationException.class).hasMessageContaining("evil.example");
        }
        assertThat(served).containsExactly("/redirect", "/landed", "/redirect", "/redirect");
    }

    @Test
    void redirectsCanBeTurnedOff() throws IOException {
        try (CloseableHttpClient http = builder().maxRedirects(0).build()) {
            assertThat(get(http, "http://status.vendor.example:" + port + "/redirect?/landed")).startsWith("302");
        }
    }

    /** With the production address check, the loopback server itself is out of reach. */
    @Test
    void loopbackIsRefusedByDefault() throws IOException {
        try (CloseableHttpClient http = SafeHttp.builder().rules(ports(port)).hostResolver(resolver()).build()) {
            assertThatThrownBy(() -> get(http, "http://127.0.0.1:" + port + "/"))
                    .isInstanceOf(RefusedDestinationException.class);
            assertThatThrownBy(() -> get(http, "http://status.vendor.example:" + port + "/"))
                    .isInstanceOf(RefusedDestinationException.class).hasMessageContaining("127.0.0.1");
        }
        assertThat(served).isEmpty();
    }

    @Test
    void onlyUrlRulesAreAccepted() {
        assertThatIllegalArgumentException().isThrownBy(() -> SafeHttp.builder().rules(under("/tmp")));
        assertThatIllegalArgumentException().isThrownBy(() -> SafeHttp.builder().maxRedirects(-1));
    }

    private SafeHttp.Builder builder() {
        return SafeHttp.builder()
                .rules(hosts("*.vendor.example"), schemes("http"), ports(port))
                .hostResolver(resolver())
                .addressCheck(address -> address.isLoopbackAddress() ? Optional.empty() : Destinations.addressProblem(address));
    }

    private CloseableHttpClient client() {
        return builder().build();
    }

    private HostResolver resolver() {
        return host -> {
            List<String> answers = dns.get(host);
            if (answers == null) {
                throw new UnknownHostException(host);
            }
            var addresses = new java.util.ArrayList<InetAddress>();
            for (String answer : answers) {
                addresses.add(InetAddress.getByName(answer));
            }
            return addresses;
        };
    }

    private static String get(CloseableHttpClient http, String url) throws IOException {
        return http.execute(new HttpGet(url), response -> response.getCode() + " " + EntityUtils.toString(response.getEntity()));
    }
}
