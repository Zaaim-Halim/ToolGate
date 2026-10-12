package io.toolgate.cli;

import io.toolgate.jdbc.JdbcSessionStore;
import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.ValueCodec;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import picocli.CommandLine.Option;

/** Where recorded tool calls come from: a JDBC session store's event log, over a window of time. */
final class History {

    @Option(names = "--codec-class",
            description = "Opens an encrypted event log: a class implementing Supplier<ValueCodec>, as the store was given.")
    String codecClass;

    @Option(names = "--classpath", split = ",",
            description = "Jars or folders with the classes named and the JDBC driver, comma-separated.")
    List<Path> classpath = new ArrayList<>();

    @Option(names = "--jdbc-url", required = true, description = "The database holding the session store's event log.")
    String jdbcUrl;

    @Option(names = "--jdbc-user", description = "The database user.")
    String jdbcUser;

    @Option(names = "--jdbc-password", description = "The database password; prefer the TOOLGATE_JDBC_PASSWORD variable.")
    String jdbcPassword;

    @Option(names = "--since", defaultValue = "7d", description = "How far back to read: 7d, 12h, 30m, or ISO-8601 (P7D). Default: ${DEFAULT-VALUE}.")
    String since;

    /**
     * The window to read.
     *
     * @param to when it ends, such as now
     * @return when it starts
     * @throws IllegalArgumentException if {@code --since} is not a duration
     */
    Instant from(Instant to) {
        return to.minus(duration(since));
    }

    /**
     * The recorded events of the window, session by session.
     *
     * @param classes where the codec and the driver are loaded from
     * @param from    the window's start
     * @param to      the window's end
     * @return the events; close the stream when done
     */
    Stream<SessionEvent> events(Classes classes, Instant from, Instant to) {
        String password = jdbcPassword != null ? jdbcPassword : System.getenv("TOOLGATE_JDBC_PASSWORD");
        ValueCodec codec = codecClass == null ? ValueCodec.unencrypted() : classes.supplied(codecClass, ValueCodec.class);
        var store = new JdbcSessionStore(new DriverDataSource(jdbcUrl, jdbcUser, password, classes.loader()), codec);
        return store.events(from, to);
    }

    /** 7d, 12h, 30m, 45s, or an ISO-8601 duration. */
    static Duration duration(String text) {
        String value = text.strip().toLowerCase(Locale.ROOT);
        try {
            if (value.startsWith("p")) {
                return Duration.parse(value.toUpperCase(Locale.ROOT));
            }
            long amount = Long.parseLong(value.substring(0, value.length() - 1));
            Duration duration = switch (value.charAt(value.length() - 1)) {
                case 'd' -> Duration.ofDays(amount);
                case 'h' -> Duration.ofHours(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 's' -> Duration.ofSeconds(amount);
                default -> throw new IllegalArgumentException("'" + text + "' does not end in d, h, m or s");
            };
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("'" + text + "' is not positive");
            }
            return duration;
        } catch (NumberFormatException | DateTimeParseException | StringIndexOutOfBoundsException unreadable) {
            throw new IllegalArgumentException("'" + text + "' is not a duration such as 7d or P7D");
        }
    }
}
