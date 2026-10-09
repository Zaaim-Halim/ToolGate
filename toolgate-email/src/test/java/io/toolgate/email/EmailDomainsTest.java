package io.toolgate.email;

import static io.toolgate.email.EmailRules.emailDomains;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.api.ArgValue;
import io.toolgate.spi.ArgumentValidator;
import java.util.Collections;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class EmailDomainsTest {

    private final ArgumentValidator rule = emailDomains("mycompany.com", "*.mycompany.com").validator();

    @Test
    void namesTheDomainThatIsNotAllowed() {
        assertThat(rule.problem(ArgValue.text("ops@mycompany.com, attacker@evil.example")))
                .contains("recipient domain evil.example is not allowed");
        assertThat(rule.problem(ArgValue.text("\"a@evil.example\"@mycompany.com"))).hasValueSatisfying(
                reason -> assertThat(reason).contains("quoted text"));
    }

    @Test
    void checksEveryElementOfAList() {
        assertThat(rule.problem(ArgValue.list(List.of(ArgValue.text("a@mycompany.com"), ArgValue.text("b@eu.mycompany.com")))))
                .isEmpty();
        assertThat(rule.problem(ArgValue.list(List.of(ArgValue.text("a@mycompany.com"), ArgValue.text("b@evil.example")))))
                .isPresent();
        assertThat(rule.problem(ArgValue.list(List.of(ArgValue.text("a@mycompany.com, b@evil.example"))))).isPresent();
        assertThat(rule.problem(ArgValue.list(List.of()))).contains("names no recipient");
        assertThat(rule.problem(ArgValue.list(List.of(ArgValue.number(1))))).contains("must be a list of strings");
        assertThat(rule.problem(ArgValue.list(Collections.nCopies(101, ArgValue.text("a@mycompany.com"))))).isPresent();
    }

    @Test
    void deniesOtherShapesAndHugeValues() {
        assertThat(rule.problem(ArgValue.number(3))).isPresent();
        assertThat(rule.problem(ArgValue.nullValue())).isPresent();
        assertThat(rule.problem(ArgValue.text("a@mycompany.com,".repeat(101) + "a@mycompany.com"))).isPresent();
        assertThat(rule.problem(ArgValue.text("a".repeat(10_000) + "@mycompany.com"))).isPresent();
    }

    @Test
    void wildcardsMatchSubdomainsOnly() {
        ArgumentValidator subdomains = emailDomains("*.mycompany.com").validator();

        assertThat(subdomains.problem(ArgValue.text("a@eu.mycompany.com"))).isEmpty();
        assertThat(subdomains.problem(ArgValue.text("a@x.eu.mycompany.com"))).isEmpty();
        assertThat(subdomains.problem(ArgValue.text("a@mycompany.com"))).isPresent();
    }

    @Test
    void patternsAreDomainNames() {
        assertThatIllegalArgumentException().isThrownBy(EmailRules::emailDomains);
        assertThatIllegalArgumentException().isThrownBy(() -> emailDomains("*"));
        assertThatIllegalArgumentException().isThrownBy(() -> emailDomains("mycompany"));
        assertThatIllegalArgumentException().isThrownBy(() -> emailDomains("my company.com"));
        assertThatIllegalArgumentException().isThrownBy(() -> emailDomains("mycomp\u0430ny.com"));
        assertThatIllegalArgumentException().isThrownBy(() -> emailDomains("a.*.mycompany.com"));
        assertThat(emailDomains("MyCompany.com", "*.mycompany.com").fingerprint())
                .isEqualTo(emailDomains("*.mycompany.com", "mycompany.com", "mycompany.com").fingerprint())
                .isEqualTo("*.mycompany.com,mycompany.com");
    }

    /** Whatever the model writes: no exception, and anything allowed satisfies the independent invariant. */
    @Property(tries = 20_000)
    void allowedValuesAreOnlyRecipientsAtAllowedDomains(@ForAll("addressLike") String text) {
        if (rule.problem(ArgValue.text(text)).isEmpty()) {
            assertThat(EmailInvariant.holds(text)).as(text).isTrue();
        }
    }

    @Property(tries = 5_000)
    void anyStringIsHandled(@ForAll String text) {
        if (rule.problem(ArgValue.text(text)).isEmpty()) {
            assertThat(EmailInvariant.holds(text)).as(text).isTrue();
        }
    }

    @Provide
    Arbitrary<String> addressLike() {
        Arbitrary<String> pieces = Arbitraries.of("ops", "a.b", "@", "mycompany.com", "evil.example", "eu.", ".", ",", ";",
                " ", "<", ">", "\"", "\\", "(", ")", ":", "[", "]", "=?", "?=", "Maria ", "\r\n", "\u0430", "xn--", "-", "+");
        return pieces.list().ofMaxSize(12).map(list -> String.join("", list));
    }
}
