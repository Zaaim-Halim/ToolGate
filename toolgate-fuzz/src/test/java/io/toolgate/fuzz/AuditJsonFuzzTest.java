package io.toolgate.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.toolgate.audit.AuditJson;

class AuditJsonFuzzTest {

    /** Text the model wrote can never end a log line, break out of its string, or change on the way. */
    @FuzzTest
    void quotedTextStaysOneStringAndReadsBack(FuzzedDataProvider data) {
        String text = data.consumeRemainingAsString();

        String quoted = AuditJson.quote(text);

        assertThat(quoted.chars().noneMatch(c -> c < 0x20 || c == 0x2028 || c == 0x2029 || Character.isSurrogate((char) c)))
                .isTrue();
        assertThat(unquote(quoted)).isEqualTo(text);
    }

    /** A JSON string reader written independently of the writer. */
    private static String unquote(String literal) {
        assertThat(literal).startsWith("\"").endsWith("\"").hasSizeGreaterThanOrEqualTo(2);
        var out = new StringBuilder();
        for (int index = 1; index < literal.length() - 1; index++) {
            char c = literal.charAt(index);
            assertThat(c).as("unescaped quote at %d", index).isNotEqualTo('"');
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = literal.charAt(++index);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(literal.substring(index + 1, index + 5), 16));
                    index += 4;
                }
                default -> throw new AssertionError("unexpected escape \\" + escape);
            }
        }
        return out.toString();
    }
}
