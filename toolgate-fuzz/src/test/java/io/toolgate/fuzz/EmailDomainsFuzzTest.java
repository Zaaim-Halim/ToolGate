package io.toolgate.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.toolgate.api.ArgValue;
import io.toolgate.email.EmailRules;
import io.toolgate.spi.ArgumentValidator;
import java.util.Locale;

class EmailDomainsFuzzTest {

    private static final ArgumentValidator RULE = EmailRules.emailDomains("mycompany.com", "*.mycompany.com").validator();

    /**
     * Whatever the model writes: no exception, and anything allowed is comma-separated plain
     * addresses, or simple names with one, at mycompany.com or a subdomain; read without Jakarta Mail.
     */
    @FuzzTest
    void allowsOnlyRecipientsAtAllowedDomains(FuzzedDataProvider data) {
        String text = data.consumeRemainingAsString();

        if (RULE.problem(ArgValue.text(text)).isEmpty()) {
            assertThat(text.chars().allMatch(c -> c >= 0x20 && c != 0x7f)).as(text).isTrue();
            for (String piece : text.split(",", -1)) {
                String address = piece.strip();
                int open = address.indexOf('<');
                if (open >= 0) {
                    assertThat(address).as(text).endsWith(">");
                    assertThat(address.substring(0, open)).as(text).doesNotContain("@");
                    address = address.substring(open + 1, address.length() - 1).strip();
                }
                assertThat(address.chars().filter(c -> c == '@').count()).as(text).isEqualTo(1);
                assertThat(address.chars().allMatch(c -> c > 0x20 && c < 0x7f)).as(text).isTrue();
                String domain = address.substring(address.indexOf('@') + 1).toLowerCase(Locale.ROOT);
                assertThat(domain.equals("mycompany.com") || domain.endsWith(".mycompany.com")).as(text).isTrue();
            }
        }
    }
}
