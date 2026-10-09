package io.toolgate.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.validate.UrlCheck.Accepted;
import io.toolgate.validate.UrlCheck.Rejected;
import io.toolgate.validate.UrlCheck.Result;
import io.toolgate.validate.UrlCheck.Rule;
import io.toolgate.validate.UrlCheck.Target;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlCheckTest {

    private static final Rule MYCOMPANY = new Rule(Set.of("https"),
            List.of(HostPattern.parse("mycompany.com"), HostPattern.parse("*.mycompany.com")), Set.of());

    @Test
    void acceptsPublicHttpsUrl() {
        assertThat(UrlCheck.check("https://status.vendor.example/api/v2/status?x=1#top", Rule.DEFAULT))
                .isEqualTo(new Accepted(new Target("https", "status.vendor.example", 443, Optional.empty())));
    }

    @Test
    void normalizesCaseAndOneTrailingDot() {
        assertThat(UrlCheck.check("HTTPS://DOCS.MyCompany.com./a", MYCOMPANY))
                .isEqualTo(new Accepted(new Target("https", "docs.mycompany.com", 443, Optional.empty())));
    }

    /** Validator probe results from the design document. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "https://docs.mycompany.com@evil.example/         | user info",
        "https://evilmycompany.com/                       | not one of the allowed hosts",
        "https://mycompany.com.evil.example/              | not one of the allowed hosts",
        "https://docs.mycompany.com../                    | not a valid host name",
        "https://dоcs.mycompany.com/                 | not printable ASCII",
        "https://xn--dcs-tdd.mycompany.com.evil.example/  | not one of the allowed hosts",
    })
    void rejectsHostTricks(String url, String reason) {
        assertThat(UrlCheck.check(url.strip(), MYCOMPANY)).isInstanceOfSatisfying(Rejected.class,
                rejected -> assertThat(rejected.reason()).contains(reason.strip()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
        "http://2852039166/", "http://0xa9fea9fe/", "http://0251.0376.0251.0376/", "http://169.254.43518/",
        "http://127.1/", "http://127.0.0.1/", "http://0.0.0.0/", "http://10.0.0.5/", "http://100.100.100.200/",
        "http://192.0.0.192/", "http://[::1]/", "http://[::ffff:169.254.169.254]/", "http://[::ffff:a9fe:a9fe]/",
        "http://[fd00:ec2::254]/", "http://[64:ff9b::a9fe:a9fe]/", "http://[2002:a9fe:a9fe::]/",
        "http://[fe80::1%25eth0]/", "http://[fe80::1]/", "http://localhost/", "http://LOCALHOST./",
        "http://api.localhost/", "http://printer.local/", "http://metadata.google.internal/",
        "http://router.home.arpa/", "http://jenkins/", "http://jenkins:8080/",
    })
    void rejectsInternalTargets(String url) {
        assertThat(UrlCheck.check(url, new Rule(Set.of("http", "https"), List.of(), Set.of(80, 8080))))
                .isInstanceOf(Rejected.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://good.example\\@evil.example/", "https://good.example\t.evil.example/", "https://good.example /",
        "https://good.example/\npath", "https://%65vil.example/", "https://evil%2eexample/", "https:evil.example",
        "https:/evil.example", "//evil.example/", "/relative", "https:///path", "https://:443/", "https://under_score.example/",
        "https://-lead.example/", "https://trail-.example/", "https://.example/", "https://a..example/", "",
        "ftp://files.example/", "file:///etc/passwd", "jar:https://x.example/a.jar!/", "gopher://x.example/",
        "javascript:alert(1)", "https://good.example:0/", "https://good.example:99999/", "https://[::1/",
    })
    void rejectsMalformedOrAmbiguousUrls(String url) {
        assertThat(UrlCheck.check(url, Rule.DEFAULT)).isInstanceOf(Rejected.class);
    }

    @Test
    void portDefaultsToTheSchemesOwnPort() {
        assertThat(UrlCheck.check("https://x.example:443/", Rule.DEFAULT)).isInstanceOf(Accepted.class);
        assertThat(UrlCheck.check("http://x.example/", Rule.DEFAULT)).isInstanceOf(Accepted.class);
        assertThat(UrlCheck.check("https://x.example:8443/", Rule.DEFAULT)).isInstanceOf(Rejected.class);
        assertThat(UrlCheck.check("https://x.example:80/", Rule.DEFAULT)).isInstanceOf(Rejected.class);
    }

    @Test
    void portsRuleReplacesTheDefault() {
        var rule = new Rule(Set.of("https"), List.of(), Set.of(8443));

        assertThat(UrlCheck.check("https://x.example:8443/", rule)).isInstanceOf(Accepted.class);
        assertThat(UrlCheck.check("https://x.example/", rule)).isInstanceOf(Rejected.class);
    }

    /** Case C: http is not allowed for the ops assistant's fetch. */
    @Test
    void schemesRuleNarrowsTheDefault() {
        var httpsOnly = new Rule(Set.of("https"), List.of(), Set.of());

        assertThat(UrlCheck.check("http://status.vendor.example/", httpsOnly)).isInstanceOfSatisfying(Rejected.class,
                rejected -> assertThat(rejected.reason()).contains("scheme 'http'"));
    }

    @Test
    void publicIpLiteralIsAllowedOnlyWithoutHostPatterns() {
        assertThat(UrlCheck.check("https://1.1.1.1/", Rule.DEFAULT))
                .isEqualTo(new Accepted(new Target("https", "1.1.1.1", 443,
                        Optional.of(new IpAddress.V4(0x01010101)))));
        assertThat(UrlCheck.check("https://[2606:4700:4700::1111]/", Rule.DEFAULT)).isInstanceOf(Accepted.class);
        assertThat(UrlCheck.check("https://1.1.1.1/", MYCOMPANY)).isInstanceOf(Rejected.class);
    }

    @Test
    void wildcardMatchesSubdomainsAtAnyDepthButNotTheApex() {
        var suppliers = new Rule(Set.of("https"), List.of(HostPattern.parse("*.suppliers.example")), Set.of());

        assertThat(UrlCheck.check("https://acme.suppliers.example/terms", suppliers)).isInstanceOf(Accepted.class);
        assertThat(UrlCheck.check("https://a.b.suppliers.example/", suppliers)).isInstanceOf(Accepted.class);
        assertThat(UrlCheck.check("https://suppliers.example/", suppliers)).isInstanceOf(Rejected.class);
        assertThat(UrlCheck.check("https://evilsuppliers.example/", suppliers)).isInstanceOf(Rejected.class);
    }

    @Test
    void ruleRejectsSchemesItCannotReasonAbout() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Rule(Set.of("ftp"), List.of(), Set.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Rule(Set.of(), List.of(), Set.of()));
    }

    @Test
    void hostPatternRejectsAddressesAndStrayWildcards() {
        assertThatIllegalArgumentException().isThrownBy(() -> HostPattern.parse("10.0.0.1"));
        assertThatIllegalArgumentException().isThrownBy(() -> HostPattern.parse("a.*.example"));
        assertThatIllegalArgumentException().isThrownBy(() -> HostPattern.parse("*"));
        assertThatIllegalArgumentException().isThrownBy(() -> HostPattern.parse("*example.com"));
    }

    /** Whatever the model writes, the check answers, and anything accepted is a public HTTP(S) target. */
    @Property
    void neverThrowsAndNeverAcceptsSpecialAddresses(@ForAll("urlish") String url) {
        Result result = UrlCheck.check(url, Rule.DEFAULT);

        if (result instanceof Accepted(Target target)) {
            assertThat(target.scheme()).isIn("http", "https");
            target.address().ifPresent(address -> assertThat(SpecialAddresses.find(address)).isEmpty());
            // Names only: an IPv6 literal such as [2000::] is public but has no dots, like a single label.
            if (target.address().isEmpty()) {
                assertThat(HostNames.internalReason(target.host())).isEmpty();
            }
        }
    }

    @Example
    void acceptsAPublicIpv6LiteralWithoutDots() {
        assertThat(UrlCheck.check("http://[2000::]/", Rule.DEFAULT)).isInstanceOf(Accepted.class);
    }

    @Provide
    Arbitrary<String> urlish() {
        Arbitrary<String> host = Arbitraries.oneOf(
                Arbitraries.strings().withChars("0123456789.x").ofMinLength(1).ofMaxLength(20),
                Arbitraries.strings().withChars("0123456789abcdef:.%").ofMinLength(1).ofMaxLength(30).map(h -> "[" + h + "]"),
                Arbitraries.strings().withChars("abcxyz019-._@\\%").ofMinLength(1).ofMaxLength(20)
                        .map(h -> h + ".example"),
                Arbitraries.of("localhost", "169.254.169.254", "[::1]", "a.internal", "1.1.1.1"));
        return host.flatMap(h -> Arbitraries.of("http://", "https://", "HTTP://", "http:", "//")
                .flatMap(prefix -> Arbitraries.of("", "/", ":80/", ":443/x", "@x/", "#@y/").map(suffix -> prefix + h + suffix)));
    }
}
