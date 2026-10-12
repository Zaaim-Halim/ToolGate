package io.toolgate.safejdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A fresh database on a real PostgreSQL or MySQL server. Each server starts once per test run, in a
 * container, and is stopped when the JVM exits; each test gets a database of its own on it.
 */
enum ServerDatabase {

    POSTGRESQL {
        @Override
        JdbcDatabaseContainer<?> container() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }

        @Override
        String url(JdbcDatabaseContainer<?> server, String database) {
            return "jdbc:postgresql://" + server.getHost() + ":" + server.getMappedPort(5432) + "/" + database;
        }
    },

    MYSQL {
        @Override
        JdbcDatabaseContainer<?> container() {
            // Lets a test create a function that writes, which binary logging otherwise refuses.
            return new MySQLContainer("mysql:9.6.0").withUsername("root")
                    .withCommand("--log-bin-trust-function-creators=1");
        }

        @Override
        String url(JdbcDatabaseContainer<?> server, String database) {
            return "jdbc:mysql://" + server.getHost() + ":" + server.getMappedPort(3306) + "/" + database;
        }
    };

    private JdbcDatabaseContainer<?> server;

    abstract JdbcDatabaseContainer<?> container();

    abstract String url(JdbcDatabaseContainer<?> server, String database);

    /**
     * Creates a new, empty database, reached as the server's administrator.
     *
     * @return the database, with its name in {@code name()}
     */
    synchronized Fresh fresh() {
        if (server == null) {
            server = container();
            server.start();
            JdbcDatabaseContainer<?> started = server;
            Runtime.getRuntime().addShutdownHook(new Thread(started::stop));
        }
        String name = "safejdbc_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = server.createConnection(""); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return new Fresh(name, new UrlDataSource(url(server, name), server.getUsername(), server.getPassword()));
    }

    /**
     * A new database.
     *
     * @param name  its name
     * @param admin a data source for the server's administrator, connected to it
     */
    record Fresh(String name, UrlDataSource admin) {}
}
