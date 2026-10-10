package io.toolgate.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.api.ArgValue;
import io.toolgate.spi.ArgumentValidator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every case of the SQL bypass corpus. */
class SqlCorpusTest {

    private static final ArgumentValidator RULE = SqlRules.readOnlySql().validator();
    private static final Pattern ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    record Case(String expect, String input, String why) {
        @Override
        public String toString() {
            return expect + " " + why;
        }
    }

    static Stream<Case> cases() throws IOException {
        try (InputStream in = SqlCorpusTest.class.getResourceAsStream("/bypass/sql-corpus-v1.txt")) {
            String text = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            var cases = new ArrayList<Case>();
            for (String line : text.split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split(" \\| ", -1);
                assertThat(fields).as(line).hasSize(4);
                assertThat(fields[0].strip()).isEqualTo("sql");
                cases.add(new Case(fields[1].strip(), unescape(fields[2].strip()), fields[3].strip()));
            }
            return cases.stream();
        }
    }

    @ParameterizedTest
    @MethodSource("cases")
    void decidesAsTheCorpusSays(Case c) {
        assertThat(RULE.problem(ArgValue.text(c.input())).isEmpty() ? "allow" : "deny").as(c.why()).isEqualTo(c.expect());
    }

    @ParameterizedTest
    @MethodSource("cases")
    void allowedInputsSatisfyTheIndependentInvariant(Case c) {
        if (c.expect().equals("allow")) {
            assertThat(SqlInvariant.holds(c.input())).as(c.why()).isTrue();
        }
    }

    static List<String> inputs() throws IOException {
        return cases().map(Case::input).toList();
    }

    private static String unescape(String input) {
        Matcher escape = ESCAPE.matcher(input);
        return escape.replaceAll(match -> Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(match.group(1), 16))));
    }
}
