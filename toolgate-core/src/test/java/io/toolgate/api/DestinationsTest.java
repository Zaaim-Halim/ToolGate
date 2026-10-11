package io.toolgate.api;

import static io.toolgate.api.policy.ArgumentRules.hosts;
import static io.toolgate.api.policy.ArgumentRules.schemes;
import static io.toolgate.api.policy.ArgumentRules.under;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class DestinationsTest {

    @Test
    void urlsAreCheckedAsUrlArgumentsAre() {
        assertThat(Destinations.urlProblem("https://status.vendor.example/api", hosts("*.vendor.example"), schemes("https")))
                .isEmpty();
        assertThat(Destinations.urlProblem("http://status.vendor.example/api", hosts("*.vendor.example"), schemes("https")))
                .isPresent();
        assertThat(Destinations.urlProblem("https://evil.example/", hosts("*.vendor.example"))).isPresent();
        assertThat(Destinations.urlProblem("http://169.254.169.254/latest/meta-data/")).hasValueSatisfying(
                reason -> assertThat(reason).contains("169.254"));
        assertThat(Destinations.urlProblem("https://docs.example/")).isEmpty();
    }

    @Test
    void onlyUrlRulesApply() {
        assertThatIllegalArgumentException().isThrownBy(() -> Destinations.urlProblem("https://x.example", under("/tmp")));
    }

    @Test
    void addressesInSpecialRangesAreRefused() throws UnknownHostException {
        assertThat(Destinations.addressProblem(InetAddress.getByName("169.254.169.254"))).hasValueSatisfying(
                reason -> assertThat(reason).contains("169.254.169.254"));
        assertThat(Destinations.addressProblem(InetAddress.getByName("10.0.0.1"))).isPresent();
        assertThat(Destinations.addressProblem(InetAddress.getByName("::1"))).isPresent();
        assertThat(Destinations.addressProblem(InetAddress.getByName("100.100.100.200"))).isPresent();
        assertThat(Destinations.addressProblem(InetAddress.getByName("93.184.215.14"))).isEmpty();
        assertThat(Destinations.addressProblem(InetAddress.getByName("2606:2800:21f:cb07:6820:80da:af6b:8b2c"))).isEmpty();
    }
}
