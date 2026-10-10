package io.toolgate.safejdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * A database that only records what SafeJdbc asks of it, for engines the build cannot run. It shows
 * which statements are sent, in which order; it cannot show that the real database then refuses a
 * write.
 */
final class RecordingDatabase {

    final List<String> log = new ArrayList<>();
    private final String productName;
    private final Optional<String> failingStatement;

    RecordingDatabase(String productName) {
        this(productName, Optional.empty());
    }

    RecordingDatabase(String productName, Optional<String> failingStatement) {
        this.productName = productName;
        this.failingStatement = failingStatement;
    }

    DataSource dataSource() {
        return proxy(DataSource.class, (proxy, method, args) -> switch (method.getName()) {
            case "getConnection" -> connection();
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private Connection connection() {
        DatabaseMetaData metaData = proxy(DatabaseMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getDatabaseProductName" -> productName;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "getMetaData" -> metaData;
            case "getAutoCommit" -> true;
            case "isReadOnly" -> false;
            case "setAutoCommit", "setReadOnly" -> record(method.getName() + " " + args[0]);
            case "rollback", "close" -> record(method.getName());
            case "createStatement" -> statement();
            case "prepareStatement" -> {
                log.add("prepare " + args[0]);
                yield preparedStatement();
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private Statement statement() {
        return proxy(Statement.class, (proxy, method, args) -> switch (method.getName()) {
            case "execute" -> {
                log.add("execute " + args[0]);
                if (failingStatement.filter(args[0]::equals).isPresent()) {
                    throw new SQLException("refused by the test");
                }
                yield false;
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private PreparedStatement preparedStatement() {
        ResultSetMetaData metaData = proxy(ResultSetMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> 0;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        ResultSet empty = proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
            case "getMetaData" -> metaData;
            case "next" -> false;
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        });
        return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
            case "setQueryTimeout", "setMaxRows" -> record(method.getName() + " " + args[0]);
            case "setFetchSize", "close" -> null;
            case "executeQuery" -> {
                log.add("executeQuery");
                yield empty;
            }
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    private Object record(String entry) {
        log.add(entry);
        return null;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(RecordingDatabase.class.getClassLoader(), new Class<?>[] {type},
                handler));
    }
}
