/**
 * An approval queue stored in the application's database, through plain JDBC.
 *
 * <p>Uses only ToolGate's public API and {@code java.sql}, so it also shows that a queue can be
 * written outside core.
 */
module io.toolgate.queue.jdbc {
    requires transitive io.toolgate.core;
    requires transitive java.sql;

    exports io.toolgate.queue.jdbc;
}
