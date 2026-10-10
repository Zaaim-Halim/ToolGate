package io.toolgate.sql;

import io.toolgate.api.policy.ArgumentRule;
import io.toolgate.api.policy.ArgumentRules;

/**
 * SQL rules for {@code ToolGatePolicy.AgentBuilder.rule}:
 *
 * <pre>{@code
 * .rule("runQuery.sql", readOnlySql())
 * }</pre>
 *
 * <p>{@code readOnlySql} is a pre-check, not the enforcement. A parser never reads SQL exactly as
 * the database does, so the check only denies: anything it cannot read, anything but one
 * {@code SELECT}, and any text that could hide a write. A query that passes may still call a
 * function with side effects, such as PostgreSQL's {@code pg_terminate_backend}; run it through
 * SafeJdbc, or as a database role that may only read.
 */
public final class SqlRules {

    private SqlRules() {}

    /**
     * Allows one query and nothing else. Denied, among others: several statements, comments,
     * {@code SELECT … INTO}, {@code FOR UPDATE}, a {@code WITH} part that writes, and any of the
     * words {@code INSERT UPDATE DELETE MERGE UPSERT INTO CREATE DROP ALTER TRUNCATE GRANT REVOKE
     * COPY CALL EXEC EXECUTE LOCK SHARE} anywhere, even inside a string. That also denies harmless
     * queries, such as {@code WHERE note = 'please update'}, and PostgreSQL's {@code #>} operator;
     * the check fails closed.
     *
     * @return the rule
     */
    public static ArgumentRule.Validated readOnlySql() {
        return ArgumentRules.validated(new ReadOnlySql());
    }
}
