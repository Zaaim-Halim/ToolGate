package io.toolgate.cli;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Connections from the first JDBC driver that accepts the URL, found with the classpath the command
 * was given. {@code DriverManager} would not see drivers that only that classpath holds.
 */
final class DriverDataSource implements DataSource {

    private final String url;
    private final String user;
    private final String password;
    private final ClassLoader loader;

    DriverDataSource(String url, String user, String password, ClassLoader loader) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.loader = loader;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return getConnection(user, password);
    }

    @Override
    public Connection getConnection(String username, String secret) throws SQLException {
        var properties = new Properties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (secret != null) {
            properties.setProperty("password", secret);
        }
        for (Driver driver : ServiceLoader.load(Driver.class, loader)) {
            if (driver.acceptsURL(url)) {
                Connection connection = driver.connect(url, properties);
                if (connection != null) {
                    return connection;
                }
            }
        }
        throw new SQLException("no JDBC driver for " + url + " on the classpath; add the driver's jar with --classpath");
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return false;
    }
}
