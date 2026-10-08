package io.toolgate.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.toolgate.validate.HostPattern;
import io.toolgate.validate.SpecialAddresses;
import io.toolgate.validate.UrlCheck;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

class UrlCheckFuzzTest {

    private static final UrlCheck.Rule MYCOMPANY = new UrlCheck.Rule(Set.of("https"),
            List.of(HostPattern.parse("mycompany.com"), HostPattern.parse("*.mycompany.com")), Set.of());

    /**
     * Whatever the model writes: no exception, and anything accepted is a public HTTP(S) target
     * that java.net.URI reads the same way, on an allowed port and host.
     */
    @FuzzTest
    void acceptsOnlyUnambiguousPublicTargets(FuzzedDataProvider data) {
        String url = data.consumeRemainingAsString();

        if (UrlCheck.check(url, UrlCheck.Rule.DEFAULT) instanceof UrlCheck.Accepted(UrlCheck.Target target)) {
            assertAccepted(url, target);
            assertThat(target.port()).isEqualTo(target.scheme().equals("https") ? 443 : 80);
        }
        if (UrlCheck.check(url, MYCOMPANY) instanceof UrlCheck.Accepted(UrlCheck.Target target)) {
            assertAccepted(url, target);
            assertThat(target.host().equals("mycompany.com") || target.host().endsWith(".mycompany.com"))
                    .as(url).isTrue();
            assertThat(target.address()).isEmpty();
        }
    }

    private static void assertAccepted(String url, UrlCheck.Target target) {
        assertThat(url.chars().allMatch(c -> c > 0x20 && c < 0x7f && c != '\\')).as(url).isTrue();
        assertThat(target.scheme()).isIn("http", "https");
        target.address().ifPresent(address -> assertThat(SpecialAddresses.find(address)).as(url).isEmpty());
        String uriHost = URI.create(url).getHost().toLowerCase(Locale.ROOT);
        String expected = uriHost.startsWith("[") ? uriHost.substring(1, uriHost.length() - 1) : uriHost;
        assertThat(expected.endsWith(".") ? expected.substring(0, expected.length() - 1) : expected)
                .as(url).isEqualTo(target.host());
        if (target.address().isEmpty()) {
            // Names only: a single-label name is internal, but an IPv6 literal has no dots at all.
            assertThat(target.host()).as(url).contains(".");
            for (String internal : List.of("localhost", "local", "internal", "home.arpa")) {
                // By whole labels: api.localhostl is a public name, api.localhost is not.
                assertThat(target.host().equals(internal) || target.host().endsWith("." + internal)).as(url).isFalse();
            }
        }
    }
}
