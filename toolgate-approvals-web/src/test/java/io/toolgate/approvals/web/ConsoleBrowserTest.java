package io.toolgate.approvals.web;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.toolgate.api.ToolGate;
import java.nio.file.Paths;
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

    private static final String SCRIPT_PAYLOAD = "<img src=x onerror=\"window.__pwned=1\"><script>window.__pwned=2</script>";

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
        assertThat(approvalId).isNotBlank();
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
    }
}
