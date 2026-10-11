package io.toolgate.approvals.web;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolGate;
import io.toolgate.spi.PendingCall;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The console in a real browser: what an attacker wrote into a call's arguments stays text, masked values
 * stay masked, and a reviewer can approve. Runs under {@code -Pe2e}, since Playwright downloads browsers.
 */
@Tag("e2e")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = BrowserTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"toolgate.approvals.ui.enabled=true", "toolgate.approvals.ui.application=Refund desk",
                "spring.ai.openai.api-key=test"})
class ConsoleBrowserTest {

    private static final String SCRIPT_PAYLOAD = "<img src=x onerror=\"window.__pwned=1\"><script>window.__pwned=2</script>"
            + " pay bi11ing@p\u0430ypal.com";

    @LocalServerPort
    private int port;
    @Autowired
    private ToolGate gate;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private final List<String> violations = new CopyOnWriteArrayList<>();

    @BeforeAll
    void startBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    void stopBrowser() {
        browser.close();
        playwright.close();
    }

    @BeforeEach
    void signIn() {
        Fixtures.REFUNDS.clear();
        context = browser.newContext(new Browser.NewContextOptions()
                .setHttpCredentials("lena", "lena-pass").setBaseURL("http://localhost:" + port));
        page = context.newPage();
        page.onConsoleMessage(message -> {
            if (message.text().contains("Content Security Policy")) {
                violations.add(message.text());
            }
        });
    }

    @Test
    void whatAnAttackerWroteStaysTextAndMaskedValuesStayMasked() {
        String approvalId = Fixtures.deferRefund(gate, "s-" + UUID.randomUUID(), SCRIPT_PAYLOAD);
        page.navigate("/toolgate/approvals");

        page.locator("button.item").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Refund an order"))
                .first().click();

        assertThat(page.locator("#arguments")).containsText(SCRIPT_PAYLOAD);
        assertThat(page.locator("#arguments")).containsText("•••• 3000");
        assertThat(page.locator("#arguments")).not().containsText("DE89370400440532013000");
        assertThat(page.locator("#provenance")).containsText("readReturnRequest");
        assertThat(page.locator("#application")).hasText("Refund desk");
        assertThat(page.evaluate("() => window.__pwned === undefined")).isEqualTo(true);
        // Kept for people reviewing a change to the console.
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target", "console.png")).setFullPage(true));
        assertThat(page.locator("#arguments img")).hasCount(0);
        assertThat(page.locator("#arguments mark.suspect")).hasCount(1);
        assertThat(page.locator("#arguments mark.suspect")).hasText("\u0430");
        assertThat(page.locator("#arguments .notes")).containsText("Cyrillic letter");

        BrowserContext dark = browser.newContext(new Browser.NewContextOptions().setHttpCredentials("lena", "lena-pass")
                .setBaseURL("http://localhost:" + port).setColorScheme(com.microsoft.playwright.options.ColorScheme.DARK));
        Page darkPage = dark.newPage();
        darkPage.navigate("/toolgate/approvals");
        darkPage.locator("button.item").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Refund an order"))
                .first().click();
        darkPage.locator("#arguments mark.suspect").waitFor();
        darkPage.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target", "console-dark.png")).setFullPage(true));
        dark.close();
        assertThat(approvalId).isNotBlank();
    }

    /** A run whose outcome was lost shows up in History, and a reviewer records what really happened. */
    @Test
    void aReviewerResolvesAnUnknownOutcomeFromHistory() throws InterruptedException {
        String approvalId = Fixtures.deferRefund(gate, "s-" + UUID.randomUUID(), "lost refund");
        gate.approve(approvalId, Principal.user("lena", Tenant.of("acme"), "finance"), Fixtures.JOB);
        gate.claim(approvalId, "crashed-node", Duration.ofMillis(1));
        Thread.sleep(20);
        gate.sweep();

        page.navigate("/toolgate/approvals");
        page.locator("#tab-history").click();
        page.locator("#history-status").selectOption("UNKNOWN");
        page.locator("#history-items button.item").first().click();
        assertThat(page.locator("#detail-status")).containsText("outcome unknown");
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target", "console-history.png")).setFullPage(true));
        page.locator("#comment").fill("the provider shows the refund");
        page.locator("#resolve-ran").click();

        assertThat(page.locator("#message")).containsText("Recorded");
        org.assertj.core.api.Assertions.assertThat(gate.withStatus(ApprovalStatus.EXECUTED))
                .extracting(PendingCall::approvalId).contains(approvalId);

        page.locator("#tab-overview").click();
        assertThat(page.locator("#numbers")).containsText("Waiting now");
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target", "console-overview.png")).setFullPage(true));
    }

    /** The graph shows the request inside its whole session, with the content that fed it. */
    @Test
    void theGraphFocusesTheRequestInItsSession() {
        String approvalId = Fixtures.deferRefund(gate, "s-" + UUID.randomUUID(), "graph refund");
        page.navigate("/toolgate/approvals");
        page.locator("button.item").filter(new com.microsoft.playwright.Locator.FilterOptions().setHasText("Refund an order"))
                .last().click();
        page.locator("#open-graph").click();

        assertThat(page.locator("#graph-view")).isVisible();
        assertThat(page.locator("svg.graph .node")).hasCount(4);
        assertThat(page.locator("svg.graph .node.subject.focused")).hasCount(1);
        assertThat(page.locator("svg.graph .subject-tag")).hasText("This request");
        assertThat(page.locator("svg.graph .edge.flow")).hasCount(1);
        assertThat(page.locator("#graph-inspector")).containsText("This request");
        assertThat(page.locator("#graph-inspector")).containsText("Untrusted content from readReturnRequest");
        page.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target", "console-graph.png")).setFullPage(true));

        page.locator("svg.graph .node.lane-0").first().click();
        assertThat(page.locator("#graph-inspector")).containsText("untrusted content");
        page.locator("#graph-copy").click();
        assertThat(page.locator("#mermaid-text")).hasValue(java.util.regex.Pattern.compile("^flowchart LR"));

        page.locator("#graph-back").click();
        assertThat(page.locator("#graph-view")).isHidden();
        assertThat(page.locator("#detail")).isVisible();
        org.assertj.core.api.Assertions.assertThat(approvalId).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(violations).isEmpty();
    }

    @Test
    void aReviewerApprovesAndTheRefundRunsOnce() {
        Fixtures.deferRefund(gate, "s-" + UUID.randomUUID(), "regular refund");
        page.navigate("/toolgate/approvals");
        page.locator("button.item").first().click();

        page.locator("#comment").fill("order checked");
        page.locator("#approve").click();

        assertThat(page.locator("#message")).containsText("Approved");
        assertThat(Fixtures.REFUNDS).containsExactly("7731:480");
        assertThat(violations).as("the page breaks none of its own Content Security Policy").isEmpty();

        BrowserContext phone = browser.newContext(new Browser.NewContextOptions().setHttpCredentials("lena", "lena-pass")
                .setBaseURL("http://localhost:" + port).setViewportSize(390, 844));
        Page phonePage = phone.newPage();
        phonePage.navigate("/toolgate/approvals");
        phonePage.locator("#items").waitFor();
        phonePage.screenshot(new Page.ScreenshotOptions().setPath(Paths.get("target", "console-phone.png")).setFullPage(true));
        org.assertj.core.api.Assertions.assertThat(phonePage.evaluate("() => document.documentElement.scrollWidth <= 390"))
                .as("no sideways scrolling on a phone").isEqualTo(true);
        phone.close();
    }
}
