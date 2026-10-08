/**
 * ToolGate's stores in the application's database, through plain JDBC: the approval queue, and
 * from here the session store.
 *
 * <p>Uses only ToolGate's public API and {@code java.sql}, so it also shows that stores can be
 * written outside core.
 */
module io.toolgate.jdbc {
    requires transitive io.toolgate.core;
    requires transitive java.sql;

    exports io.toolgate.jdbc;
}
