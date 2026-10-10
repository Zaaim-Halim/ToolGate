/**
 * SafeJdbc, for tool authors: runs a model's query in a read-only transaction, with a timeout and
 * limits on what comes back, after the same pre-check as the {@code readOnlySql} rule.
 */
module io.toolgate.safejdbc {
    requires transitive io.toolgate.sql;
    requires transitive java.sql;

    exports io.toolgate.safejdbc;
}
