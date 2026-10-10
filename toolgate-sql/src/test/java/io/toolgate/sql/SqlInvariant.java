package io.toolgate.sql;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What an allowed value must be, written without JSqlParser or {@link SqlText}: printable text that
 * starts as a query, holds no comment marker, no semicolon but a last one, and no writing word.
 */
final class SqlInvariant {

    private static final Pattern WRITING_WORD = Pattern.compile(
            "(?<![\\p{L}_])(INSERT|UPDATE|DELETE|MERGE|UPSERT|INTO|CREATE|DROP|ALTER|TRUNCATE|GRANT|REVOKE"
                    + "|COPY|CALL|EXEC|EXECUTE|LOCK|SHARE)(?![\\p{L}_])");
    private static final Pattern STARTS_AS_QUERY = Pattern.compile("^(SELECT|WITH|\\()", Pattern.CASE_INSENSITIVE);

    private SqlInvariant() {}

    static boolean holds(String sql) {
        String body = sql.strip();
        if (body.endsWith(";")) {
            body = body.substring(0, body.length() - 1);
        }
        return sql.length() <= 10_000
                && sql.codePoints().allMatch(c -> c >= 0x20 || c == '\t' || c == '\n' || c == '\r')
                && sql.codePoints().noneMatch(c -> c == 0x7f || c == 0x2028 || c == 0x2029
                        || Character.getType(c) == Character.FORMAT)
                && !body.contains(";")
                && !sql.contains("--") && !sql.contains("/*") && !sql.contains("*/") && !sql.contains("#")
                && !WRITING_WORD.matcher(sql.toUpperCase(Locale.ROOT)).find()
                && STARTS_AS_QUERY.matcher(body).find();
    }
}
