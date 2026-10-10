/**
 * The {@code readOnlySql} rule: a pre-check that denies anything but a single query. It only ever
 * denies; what a query is allowed to do is enforced by the database, through SafeJdbc or a
 * read-only role.
 */
module io.toolgate.sql {
    requires transitive io.toolgate.core;
    requires net.sf.jsqlparser;

    exports io.toolgate.sql;
}
