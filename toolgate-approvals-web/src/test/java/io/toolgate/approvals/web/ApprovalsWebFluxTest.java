package io.toolgate.approvals.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;

import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ToolGate;
import io.toolgate.spi.PendingCall;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The same API on Spring WebFlux, against the same contract. */
@SpringBootTest(classes = ReactiveTestApplication.class,
        properties = {"spring.main.web-application-type=reactive", "toolgate.approvals.ui.enabled=true",
                "spring.ai.openai.api-key=test"})
@AutoConfigureWebTestClient
class ApprovalsWebFluxTest {

    @Autowired
    private WebTestClient client;
    @Autowired
    private ToolGate gate;

    private String approvalId;

    @BeforeEach
    void defer() {
        Fixtures.REFUNDS.clear();
        approvalId = Fixtures.deferRefund(gate, "s-" + UUID.randomUUID(), "refund please");
    }

    @Test
    void nobodySignedInGetsNothing() {
        client.get().uri("/toolgate/api/approvals").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void theInboxIsMaskedAndScopedToTheTenant() {
        lena().get().uri("/toolgate/api/approvals").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[?(@.approvalId == '%s')].arguments[?(@.name == 'iban')].display".formatted(approvalId))
                .isEqualTo("•••• 3000");
        gus().get().uri(builder -> builder.path("/toolgate/api/approvals/item").queryParam("id", approvalId).build())
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void anApprovalWithTheDigestRunsTheRefundOnce() {
        String digest = new String(lena().get()
                .uri(builder -> builder.path("/toolgate/api/approvals/item").queryParam("id", approvalId).build())
                .exchange().expectBody().jsonPath("$.digest").isNotEmpty().returnResult().getResponseBody());
        String value = com.jayway.jsonpath.JsonPath.read(digest, "$.digest");

        decision(lena(), value, null).exchange().expectStatus().isOk().expectBody().jsonPath("$.outcome").isEqualTo("released");

        assertThat(Fixtures.REFUNDS).containsExactly("7731:480");
        assertThat(gate.withStatus(ApprovalStatus.EXECUTED)).extracting(PendingCall::approvalId).contains(approvalId);
    }

    @Test
    void aDecisionAnotherSiteCouldSendIsRefused() {
        lena().post().uri("/toolgate/api/approvals/decisions").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body("reject", "x")).exchange().expectStatus().isForbidden();
        decision(lena(), "x", "https://evil.example").exchange().expectStatus().isForbidden();
        assertThat(Fixtures.REFUNDS).isEmpty();
    }

    @Test
    void everyResponseCarriesTheConsoleHeaders() {
        for (String path : new String[] {"/toolgate/api/approvals", "/toolgate/approvals/index.html"}) {
            lena().get().uri(path).exchange().expectStatus().isOk().expectHeader()
                    .valueMatches("Content-Security-Policy", ".*frame-ancestors 'none'.*")
                    .expectHeader().valueEquals("X-Frame-Options", "DENY")
                    .expectHeader().valueEquals("Cache-Control", "no-store");
        }
    }

    private WebTestClient.RequestHeadersSpec<?> decision(WebTestClient as, String digest, String origin) {
        WebTestClient.RequestBodySpec request = as.post().uri("/toolgate/api/approvals/decisions")
                .contentType(MediaType.APPLICATION_JSON).header(RequestGuard.HEADER, "1");
        if (origin != null) {
            request = request.header("Origin", origin);
        }
        return request.bodyValue(body("approve", digest));
    }

    private String body(String kind, String digest) {
        return "{\"approvalId\":\"" + approvalId + "\",\"kind\":\"" + kind + "\",\"digest\":\"" + digest + "\"}";
    }

    private WebTestClient lena() {
        return client.mutateWith(mockUser("lena").roles("finance"));
    }

    private WebTestClient gus() {
        return client.mutateWith(mockUser("gus").roles("finance"));
    }
}
