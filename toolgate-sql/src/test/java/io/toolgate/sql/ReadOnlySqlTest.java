package io.toolgate.sql;

import static io.toolgate.sql.SqlRules.readOnlySql;
import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.ArgValue;
import io.toolgate.spi.ArgumentValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class ReadOnlySqlTest {

    private final ArgumentValidator rule = readOnlySql().validator();

    @Test
    void namesWhatIsWrong() {
        assertThat(rule.problem(ArgValue.text("DELETE FROM orders"))).contains(
                "SQL contains DELETE, which writes, locks or calls a procedure");
        assertThat(rule.problem(ArgValue.text("SELECT 1; SELECT 2"))).contains("SQL contains more than one statement");
        assertThat(rule.problem(ArgValue.text("SELECT 1 -- x"))).contains("SQL contains a comment marker (--, /*, */ or #)");
        assertThat(rule.problem(ArgValue.text("SELECT * FROM orders FOR NO KEY UPDATE"))).isPresent();
        assertThat(rule.problem(ArgValue.text("SELEC 1"))).contains("SQL must start with SELECT, WITH or (");
        assertThat(rule.problem(ArgValue.text("SELECT FROM WHERE"))).contains("SQL cannot be read as a query");
    }

    @Test
    void theTreeDeniesWhatTheWordsMiss() {
        // These start as a query and hold no writing word, so only the statement tree catches them.
        assertThat(rule.problem(ArgValue.text("(VALUES (1))"))).contains("SQL is a Values, not a plain query");
        assertThat(rule.problem(ArgValue.text("(SELECT 1) UNION (VALUES (2))"))).contains("SQL is a Values, not a plain query");
        assertThat(rule.problem(ArgValue.text("SELECT * FROM orders FOR KEY SHAR"))).isPresent();
    }

    @Test
    void deniesOtherShapes() {
        assertThat(rule.problem(ArgValue.number(1))).contains("must be a string");
        assertThat(rule.problem(ArgValue.nullValue())).contains("must be a string");
        assertThat(rule.problem(ArgValue.list(List.of(ArgValue.text("SELECT 1"))))).contains("must be a string");
    }

    @Test
    void boundsTheInputBeforeParsing() {
        assertThat(rule.problem(ArgValue.text("SELECT 1" + " ".repeat(SqlText.MAX_LENGTH)))).hasValueSatisfying(
                reason -> assertThat(reason).contains("longer than"));
    }

    @Test
    void deniesNestingThatOverflowsTheStack() throws InterruptedException {
        // CASE inside CASE nests without parentheses, so only the length limit bounds it; on a small stack
        // the parser overflows well within that limit, and the thread goes on checking.
        String cases = "SELECT " + "CASE WHEN 1 = 1 THEN ".repeat(390) + "1" + " END".repeat(390);
        var problems = new ArrayList<Optional<String>>();
        Thread smallStack = new Thread(null, () -> {
            problems.add(rule.problem(ArgValue.text(cases)));
            problems.add(rule.problem(ArgValue.text("SELECT 1")));
        }, "small-stack", 128 * 1024);

        smallStack.start();
        smallStack.join();

        assertThat(cases.length()).isLessThanOrEqualTo(SqlText.MAX_LENGTH);
        assertThat(problems).containsExactly(Optional.of("SQL nests too deeply to read"), Optional.empty());
    }

    @Test
    void hasNoConfiguration() {
        assertThat(rule.kind()).isEqualTo("readOnlySql");
        assertThat(rule.fingerprint()).isEmpty();
    }

    @Property(tries = 2_000)
    void anythingAllowedSatisfiesTheInvariant(@ForAll("queryish") String sql) {
        if (rule.problem(ArgValue.text(sql)).isEmpty()) {
            assertThat(SqlInvariant.holds(sql)).as(sql).isTrue();
        }
    }

    @Provide
    Arbitrary<String> queryish() {
        Arbitrary<String> pieces = Arbitraries.of("SELECT", "select", "WITH", "t", "AS", "(", ")", "*", "id", "FROM",
                "orders", "WHERE", "=", "1", "'a'", "';'", ";", ",", "UNION", "INTO", "into", "x", "FOR", "UPDATE",
                "update", "DELETE", "--", "/*", "*/", "/*!", "#", "\\", "'", "\"", "\n", "​", "\u0000", "RETURNING",
                "JOIN", "ON", "IN", "LIMIT", "CASE", "WHEN", "THEN", "END", "updated_at", "SHARE", "OUTFILE", "@v");
        return pieces.list().ofMinSize(1).ofMaxSize(20).map(list -> String.join(" ", list));
    }
}
