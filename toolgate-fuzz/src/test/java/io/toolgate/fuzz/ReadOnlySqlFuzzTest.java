package io.toolgate.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.toolgate.api.ArgValue;
import io.toolgate.spi.ArgumentValidator;
import io.toolgate.sql.SqlRules;
import java.util.Locale;
import java.util.regex.Pattern;

class ReadOnlySqlFuzzTest {

    private static final ArgumentValidator RULE = SqlRules.readOnlySql().validator();
    private static final Pattern WRITING_WORD = Pattern.compile(
            "(?<![\\p{L}_])(INSERT|UPDATE|DELETE|MERGE|UPSERT|INTO|CREATE|DROP|ALTER|TRUNCATE|GRANT|REVOKE"
                    + "|COPY|CALL|EXEC|EXECUTE|LOCK|SHARE)(?![\\p{L}_])");

    /**
     * Whatever the model writes: no exception or error, and anything allowed starts as a query, holds no
     * comment marker, no semicolon but a last one, and no writing word; read without JSqlParser.
     */
    @FuzzTest
    void allowsOnlyASingleQuery(FuzzedDataProvider data) {
        String sql = data.consumeRemainingAsString();

        if (RULE.problem(ArgValue.text(sql)).isEmpty()) {
            String body = sql.strip();
            if (body.endsWith(";")) {
                body = body.substring(0, body.length() - 1);
            }
            assertThat(body).as(sql).doesNotContain(";");
            assertThat(sql).as(sql).doesNotContain("--").doesNotContain("/*").doesNotContain("*/").doesNotContain("#");
            assertThat(WRITING_WORD.matcher(sql.toUpperCase(Locale.ROOT)).find()).as(sql).isFalse();
            assertThat(body.toUpperCase(Locale.ROOT)).as(sql).matches("(?s)^(SELECT|WITH|\\().*");
        }
    }
}
