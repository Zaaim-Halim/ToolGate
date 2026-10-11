package io.toolgate.approvals.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.ToolGate;
import io.toolgate.spi.PendingCall;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The REST API and the console's files on Spring MVC, in an application that left the path open. */
@SpringBootTest(classes = WebTestApplication.class,
        properties = {"toolgate.approvals.ui.enabled=true", "toolgate.approvals.ui.application=Refund desk",
                "spring.ai.openai.api-key=test"})
@AutoConfigureMockMvc
class ApprovalsMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ToolGate gate;

    private String approvalId;

    @BeforeEach
    void defer() {
        Fixtures.REFUNDS.clear();
        Fixtures.RAN_AS.clear();
        approvalId = Fixtures.deferRefund(gate, "s-" + UUID.randomUUID(), "customer says <img src=x onerror=alert(1)>");
    }

    /** The application's security let the path through; the API itself refuses anyone not signed in. */
    @Test
    void nobodySignedInGetsNothing() throws Exception {
        mvc.perform(get("/toolgate/api/approvals")).andExpect(status().isUnauthorized());
        mvc.perform(get("/toolgate/api/approvals/item").param("id", approvalId)).andExpect(status().isUnauthorized());
        mvc.perform(decision("approve", "sha256:x")).andExpect(status().isUnauthorized());
    }

    @Test
    void theInboxIsMaskedAndTellsWhoIsSignedIn() throws Exception {
        mvc.perform(get("/toolgate/api/approvals").with(lena()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.approvalId == '%s')].title", approvalId).value("Refund an order"))
                .andExpect(jsonPath("$[?(@.approvalId == '%s')].arguments[?(@.name == 'iban')].display", approvalId)
                        .value("•••• 3000"))
                .andExpect(jsonPath("$[?(@.approvalId == '%s')].mayDecide", approvalId).value(true));
        mvc.perform(get("/toolgate/api/approvals/me").with(lena()))
                .andExpect(jsonPath("$.viewer.id").value("lena"))
                .andExpect(jsonPath("$.viewer.tenant").value("acme"))
                .andExpect(jsonPath("$.application").value("Refund desk"));
    }

    @Test
    void aReviewerOfAnotherTenantSeesNothingAndCannotDecide() throws Exception {
        mvc.perform(get("/toolgate/api/approvals").with(gus()))
                .andExpect(jsonPath("$[?(@.approvalId == '%s')]", approvalId).isEmpty());
        mvc.perform(get("/toolgate/api/approvals/item").param("id", approvalId).with(gus())).andExpect(status().isNotFound());
        mvc.perform(decision("reject", null).with(gus())).andExpect(status().isNotFound());
        mvc.perform(get("/toolgate/api/approvals/item").param("id", "no-such-call").with(lena()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anApprovalWithTheDigestRunsTheRefundOnce() throws Exception {
        String digest = digestFor(lena());

        mvc.perform(decision("approve", digest).with(lena()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("released"));
        mvc.perform(decision("approve", digest).with(lena()))
                .andExpect(jsonPath("$.outcome").value("already-decided"))
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        assertThat(Fixtures.REFUNDS).containsExactly("7731:480");
        assertThat(Fixtures.RAN_AS).as("the tool never acts with the reviewer's authorities").containsExactly("nobody");
        assertThat(gate.withStatus(ApprovalStatus.EXECUTED)).extracting(PendingCall::approvalId).contains(approvalId);
    }

    /** Behind a proxy that ends TLS, the browser's origin is https while the application sees http. */
    @Test
    void aSameOriginDecisionThroughATlsProxyPasses() throws Exception {
        mvc.perform(decision("approve", digestFor(lena())).with(lena())
                        .header("Host", "approvals.example").header("Origin", "https://approvals.example"))
                .andExpect(jsonPath("$.outcome").value("released"));
    }

    /** The API hands attacker text back verbatim, as JSON a browser will not sniff into markup. */
    @Test
    void attackerTextComesBackAsDataNeverAsMarkup() throws Exception {
        String[] payloads = {"<script>alert(1)</script>", "\"><img src=x onerror=alert(1)>", "javascript:alert(1)",
                "</textarea><svg onload=alert(1)>", "{{constructor.constructor('alert(1)')()}}", "\u202Egnp.exe"};
        for (String payload : payloads) {
            String id = Fixtures.deferRefund(gate, "x-" + UUID.randomUUID(), payload);
            mvc.perform(get("/toolgate/api/approvals/item").param("id", id).with(lena()))
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(jsonPath("$.arguments[?(@.name == 'note')].display").value(payload));
        }
    }

    @Test
    void anApprovalNeedsTheDigestOfWhatWasShown() throws Exception {
        mvc.perform(decision("approve", null).with(lena())).andExpect(status().isBadRequest());
        mvc.perform(decision("approve", "sha256:" + "0".repeat(64)).with(lena()))
                .andExpect(jsonPath("$.outcome").value("not-permitted"))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("reload")));
        assertThat(Fixtures.REFUNDS).isEmpty();
    }

    @Test
    void aSuperAdminMustJustifyAndARejectionNeedsAReason() throws Exception {
        mvc.perform(decision("approve", digestFor(root())).with(root()))
                .andExpect(jsonPath("$.outcome").value("not-permitted"))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("justify")));
        mvc.perform(decision("reject", null).with(lena())).andExpect(status().isBadRequest());
        mvc.perform(post("/toolgate/api/approvals/decisions").with(lena()).contentType(MediaType.APPLICATION_JSON)
                        .header(RequestGuard.HEADER, "1")
                        .content("{\"approvalId\":\"" + approvalId + "\",\"kind\":\"reject\",\"comment\":\"duplicate\"}"))
                .andExpect(jsonPath("$.outcome").value("done"));
    }

    /** CSRF protection that holds even though the application turned Spring Security's off. */
    @Test
    void aDecisionAnotherSiteCouldSendIsRefused() throws Exception {
        String digest = digestFor(lena());
        mvc.perform(post("/toolgate/api/approvals/decisions").with(lena()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("approve", digest)))
                .andExpect(status().isForbidden());
        mvc.perform(decision("approve", digest).with(lena()).header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/toolgate/api/approvals/decisions").with(lena()).contentType(MediaType.TEXT_PLAIN)
                        .header(RequestGuard.HEADER, "1").content(body("approve", digest)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(403, 415));
        mvc.perform(decision("approve", digest).with(lena()).header("Origin", "http://localhost"))
                .andExpect(jsonPath("$.outcome").value("released"));
    }

    @Test
    void everyResponseCarriesTheConsoleHeaders() throws Exception {
        for (String path : new String[] {"/toolgate/api/approvals", "/toolgate/approvals/index.html", "/toolgate/approvals/app.js"}) {
            mvc.perform(get(path).with(lena()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self'")))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get("/toolgate/approvals").with(lena()))
                .andExpect(status().isFound()).andExpect(header().string("Location", ConsoleRedirect.PAGE));
    }

    private String digestFor(org.springframework.test.web.servlet.request.RequestPostProcessor viewer) throws Exception {
        String json = mvc.perform(get("/toolgate/api/approvals/item").param("id", approvalId).with(viewer))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(json, "$.digest");
    }

    private MockHttpServletRequestBuilder decision(String kind, String digest) {
        return post("/toolgate/api/approvals/decisions").contentType(MediaType.APPLICATION_JSON)
                .header(RequestGuard.HEADER, "1").content(body(kind, digest));
    }

    private String body(String kind, String digest) {
        return "{\"approvalId\":\"" + approvalId + "\",\"kind\":\"" + kind + "\""
                + (digest == null ? "" : ",\"digest\":\"" + digest + "\"") + "}";
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor lena() {
        return user("lena").roles("finance");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor gus() {
        return user("gus").roles("finance");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor root() {
        return user("root").roles("toolgate-admin");
    }
}
