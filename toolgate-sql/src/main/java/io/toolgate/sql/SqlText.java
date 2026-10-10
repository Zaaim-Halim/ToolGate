package io.toolgate.sql;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Checks on the raw text, before any parsing. They read the whole string, string literals
 * included, because where a literal ends depends on the database: MySQL reads {@code '\'} as an
 * unfinished string, PostgreSQL as a finished one. Text that any database could read as a comment,
 * a second statement or a writing keyword is denied wherever it appears.
 */
final class SqlText {

    static final int MAX_LENGTH = 10_000;
    /** JSqlParser overflows a default thread stack between 1,000 and 3,000 levels of parentheses. */
    static final int MAX_NESTING = 100;

    /**
     * Words that start, or are part of, a statement that writes, locks or calls a procedure.
     * {@code INTO} also covers MySQL's {@code INTO OUTFILE}, which JSqlParser's tree does not show.
     */
    private static final Set<String> WRITING_WORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "INTO", "CREATE", "DROP", "ALTER", "TRUNCATE",
            "GRANT", "REVOKE", "COPY", "CALL", "EXEC", "EXECUTE", "LOCK", "SHARE");

    private SqlText() {}

    static Optional<String> problem(String sql) {
        if (sql.isBlank()) {
            return Optional.of("is empty");
        }
        if (sql.length() > MAX_LENGTH) {
            return Optional.of("is longer than " + MAX_LENGTH + " characters");
        }
        return firstPresent(characters(sql), comments(sql), statements(sql), nesting(sql), words(sql), start(sql));
    }

    @SafeVarargs
    private static Optional<String> firstPresent(Optional<String>... problems) {
        for (Optional<String> problem : problems) {
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    /** Control and invisible characters; tabs and line breaks are fine. */
    private static Optional<String> characters(String sql) {
        return sql.codePoints()
                .filter(c -> (c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7f
                        || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029)
                .mapToObj(c -> "contains the control or invisible character U+" + String.format("%04X", c))
                .findFirst();
    }

    /**
     * Any comment marker. JSqlParser drops MySQL's {@code /*! … *}{@code /}, which MySQL runs as
     * code, and {@code #} starts a comment in MySQL only.
     */
    private static Optional<String> comments(String sql) {
        return sql.contains("--") || sql.contains("/*") || sql.contains("*/") || sql.indexOf('#') >= 0
                ? Optional.of("contains a comment marker (--, /*, */ or #)")
                : Optional.empty();
    }

    /** One trailing semicolon is allowed; any other separates statements. */
    private static Optional<String> statements(String sql) {
        String body = sql.strip();
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1);
        }
        return body.indexOf(';') >= 0 ? Optional.of("contains more than one statement") : Optional.empty();
    }

    /** JSqlParser also reads dialect forms such as Teradata's {@code SEL} as a query; only the standard starts pass. */
    private static Optional<String> start(String sql) {
        String body = sql.strip();
        if (body.startsWith("(")) {
            return Optional.empty();
        }
        int end = 0;
        while (end < body.length() && isWordChar(body.charAt(end))) {
            end++;
        }
        String first = body.substring(0, end).toUpperCase(Locale.ROOT);
        return first.equals("SELECT") || first.equals("WITH")
                ? Optional.empty()
                : Optional.of("must start with SELECT, WITH or (");
    }

    private static Optional<String> nesting(String sql) {
        int depth = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(' && ++depth > MAX_NESTING) {
                return Optional.of("nests parentheses deeper than " + MAX_NESTING);
            }
            if (c == ')') {
                depth--;
            }
        }
        return Optional.empty();
    }

    /**
     * Words are maximal runs of letters and {@code _}, so {@code updated_at} is one word. Digits and
     * {@code $} split words: PostgreSQL 14 and older read {@code 1INTO} as {@code 1 INTO}, while
     * JSqlParser reads one name. Names such as {@code col1update} are denied with it.
     */
    private static Optional<String> words(String sql) {
        int start = -1;
        for (int i = 0; i <= sql.length(); i++) {
            boolean wordChar = i < sql.length() && isWordChar(sql.charAt(i));
            if (wordChar && start < 0) {
                start = i;
            } else if (!wordChar && start >= 0) {
                String word = sql.substring(start, i).toUpperCase(Locale.ROOT);
                if (WRITING_WORDS.contains(word)) {
                    return Optional.of("contains " + word + ", which writes, locks or calls a procedure");
                }
                start = -1;
            }
        }
        return Optional.empty();
    }

    private static boolean isWordChar(char c) {
        return Character.isLetter(c) || c == '_';
    }
}
