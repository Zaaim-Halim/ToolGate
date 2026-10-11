package io.toolgate.approvals.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Which requests could have come from a page on another site. */
class RequestGuardTest {

    @Test
    void aSameOriginJsonRequestWithTheHeaderPasses() {
        assertThatCode(() -> RequestGuard.check("application/json", "1", "https://ops.example", "ops.example:443"))
                .doesNotThrowAnyException();
        assertThatCode(() -> RequestGuard.check("application/json; charset=utf-8", "1", "http://localhost:8080",
                "localhost:8080")).doesNotThrowAnyException();
        assertThatCode(() -> RequestGuard.check("application/json", "1", null, "ops.example:443"))
                .as("a browser that sends no Origin still had to set the header").doesNotThrowAnyException();
        assertThatCode(() -> RequestGuard.check("application/json", "1", "http://[::1]:8080", "[::1]:8080"))
                .doesNotThrowAnyException();
        assertThatCode(() -> RequestGuard.check("application/json", "1", "https://app.example", "app.example"))
                .as("a proxy that ends TLS: the Host header has no port, the origin is https")
                .doesNotThrowAnyException();
    }

    @Test
    void anythingAFormOrAnotherOriginCouldSendIsRefused() {
        assertThatThrownBy(() -> RequestGuard.check("application/x-www-form-urlencoded", "1", null, "ops.example:443"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RequestGuard.check("application/json", null, null, "ops.example:443"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RequestGuard.check("application/json", "1", "https://evil.example", "ops.example:443"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RequestGuard.check("application/json", "1", "https://ops.example:8443", "ops.example:443"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RequestGuard.check("application/json", "1", "null", "ops.example:443"))
                .as("a sandboxed frame or a file: page").isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RequestGuard.check(null, "1", null, "ops.example:443")).isInstanceOf(ApiException.class);
    }
}
