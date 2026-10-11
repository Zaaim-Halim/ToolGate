package io.toolgate.safehttp;

import io.toolgate.api.Destinations;
import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.spi.HostResolver;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

/**
 * An Apache HttpClient 5 client for tools that fetch a URL a model chose:
 *
 * <pre>{@code
 * CloseableHttpClient http = SafeHttp.builder()
 *         .rules(hosts("*.vendor.example"), schemes("https"))
 *         .build();
 * }</pre>
 *
 * <p>Every request, and every redirect it follows, is checked against the same rules as a URL
 * argument with them. Every host name is resolved once, by SafeHttp; if any address it resolves to is
 * in a special-purpose range, such as loopback, a private network or the cloud metadata address, the
 * request fails; otherwise the client connects to exactly those addresses, so a name cannot resolve to
 * a public address for the check and a private one for the connection. Proxies, whose DNS SafeHttp
 * cannot see, are never used. A refused request fails with an {@link java.io.IOException} before
 * anything is sent.
 *
 * <p>Not covered: what the server returns, and a host that resolves to a public address it does not
 * own. The JDK's own {@code HttpClient} cannot pin addresses per client; tools using it get only the
 * URL checks.
 */
public final class SafeHttp {

    private SafeHttp() {}

    /**
     * Starts a client.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds a SafeHttp client. */
    public static final class Builder {

        private static final int DEFAULT_MAX_REDIRECTS = 5;
        private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

        private List<ArgumentRule> rules = List.of();
        private int maxRedirects = DEFAULT_MAX_REDIRECTS;
        private Duration timeout = DEFAULT_TIMEOUT;
        private HostResolver resolver = HostResolver.system();
        private Function<InetAddress, Optional<String>> addressCheck = Destinations::addressProblem;

        private Builder() {}

        /**
         * The URL rules every request and redirect must pass: {@code hosts}, {@code schemes} and
         * {@code ports}. Without rules, any public host over {@code http} or {@code https} on the
         * scheme's own port.
         *
         * @param urlRules the rules
         * @return this builder
         * @throws IllegalArgumentException if a rule is not a URL rule
         */
        public Builder rules(ArgumentRule... urlRules) {
            List<ArgumentRule> checked = List.of(urlRules);
            // Fails now, not on the first request, if a rule is not a URL rule.
            Destinations.urlProblem("https://example.com/", urlRules);
            this.rules = checked;
            return this;
        }

        /**
         * How many redirects a request may follow; each is checked like the first request.
         *
         * @param redirects at least 0
         * @return this builder
         */
        public Builder maxRedirects(int redirects) {
            if (redirects < 0) {
                throw new IllegalArgumentException("maxRedirects must not be negative, was " + redirects);
            }
            this.maxRedirects = redirects;
            return this;
        }

        /**
         * How long connecting and each wait for the response may take.
         *
         * @param duration a positive duration
         * @return this builder
         */
        public Builder timeout(Duration duration) {
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("timeout must be positive, was " + duration);
            }
            this.timeout = duration;
            return this;
        }

        /**
         * Resolves host names, by default with the JVM's resolver.
         *
         * @param hostResolver the resolver
         * @return this builder
         */
        public Builder hostResolver(HostResolver hostResolver) {
            this.resolver = Objects.requireNonNull(hostResolver, "hostResolver");
            return this;
        }

        /** For tests: which addresses may be connected to. */
        Builder addressCheck(Function<InetAddress, Optional<String>> check) {
            this.addressCheck = Objects.requireNonNull(check, "check");
            return this;
        }

        /**
         * Builds the client; close it when done.
         *
         * @return the client
         */
        public CloseableHttpClient build() {
            Timeout limit = Timeout.of(timeout);
            var connections = PoolingHttpClientConnectionManagerBuilder.create()
                    .setDnsResolver(new CheckedDnsResolver(resolver, addressCheck))
                    .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(limit).setSocketTimeout(limit).build())
                    .build();
            return HttpClients.custom()
                    .setConnectionManager(connections)
                    // Every request the client sends, redirects included, before it resolves or connects.
                    .addExecInterceptorBefore(ChainElement.PROTOCOL.name(), "toolgate-url-rules", new UrlRules(rules))
                    .setDefaultRequestConfig(RequestConfig.custom()
                            .setMaxRedirects(maxRedirects)
                            .setRedirectsEnabled(maxRedirects > 0)
                            .setResponseTimeout(limit)
                            .setConnectionRequestTimeout(limit)
                            .build())
                    // No proxy, whichever the system defines: a proxy resolves names where SafeHttp cannot check.
                    .setProxySelector(null)
                    .build();
        }
    }
}
