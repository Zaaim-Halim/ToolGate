package io.toolgate.cli;

import io.toolgate.api.PolicyReplay;
import io.toolgate.api.ReplayReport;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.jdbc.JdbcSessionStore;
import io.toolgate.spi.OwnershipResolver;
import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.ToolCatalog;
import io.toolgate.spi.ValueCodec;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Replays the tool calls a JDBC session store recorded through a proposed policy, and reports which
 * decisions would change, as in the design's Case G.
 */
@Command(name = "replay", mixinStandardHelpOptions = true,
        description = "Replay recorded tool calls through a proposed policy and report what would change.")
final class ReplayCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = "--policy-class", required = true,
            description = "The proposed policy: a class implementing Supplier<ToolGatePolicy>.")
    String policyClass;

    @Option(names = "--catalog-class",
            description = "Tool definitions, needed when the policy has argument rules: a class implementing Supplier<ToolCatalog>.")
    String catalogClass;

    @Option(names = "--owners-class",
            description = "Ownership lookups, needed for ownership rules: a class implementing Supplier<OwnershipResolver>.")
    String ownersClass;

    @Option(names = "--codec-class",
            description = "Opens an encrypted event log: a class implementing Supplier<ValueCodec>, as the store was given.")
    String codecClass;

    @Option(names = "--classpath", split = ",",
            description = "Jars or folders with those classes and the JDBC driver, comma-separated.")
    List<Path> classpath = new ArrayList<>();

    @Option(names = "--jdbc-url", required = true, description = "The database holding the session store's event log.")
    String jdbcUrl;

    @Option(names = "--jdbc-user", description = "The database user.")
    String jdbcUser;

    @Option(names = "--jdbc-password", description = "The database password; prefer the TOOLGATE_JDBC_PASSWORD variable.")
    String jdbcPassword;

    @Option(names = "--since", defaultValue = "7d", description = "How far back to replay: 7d, 12h, 30m, or ISO-8601 (P7D). Default: ${DEFAULT-VALUE}.")
    String since;

    @Option(names = "--fail-on-change", description = "Exit with 1 if any decision would change, for CI.")
    boolean failOnChange;

    @Override
    public Integer call() {
        Instant to = Instant.now();
        Instant from;
        try {
            from = to.minus(duration(since));
        } catch (IllegalArgumentException badDuration) {
            spec.commandLine().getErr().println("invalid --since: " + badDuration.getMessage());
            return 2;
        }
        try (var classes = new Classes(classpath)) {
            ToolGatePolicy policy = classes.supplied(policyClass, ToolGatePolicy.class);
            PolicyReplay.Builder replay = PolicyReplay.builder(policy);
            if (catalogClass != null) {
                replay.toolCatalog(classes.supplied(catalogClass, ToolCatalog.class));
            }
            if (ownersClass != null) {
                replay.ownershipResolver(classes.supplied(ownersClass, OwnershipResolver.class));
            }
            String password = jdbcPassword != null ? jdbcPassword : System.getenv("TOOLGATE_JDBC_PASSWORD");
            ValueCodec codec = codecClass == null ? ValueCodec.unencrypted() : classes.supplied(codecClass, ValueCodec.class);
            var history = new JdbcSessionStore(new DriverDataSource(jdbcUrl, jdbcUser, password, classes.loader()), codec);
            ReplayReport report;
            try (Stream<SessionEvent> events = history.events(from, to)) {
                report = replay.build().replay(events);
            }
            spec.commandLine().getOut().println(report.summary());
            return failOnChange && !report.changes().isEmpty() ? 1 : 0;
        } catch (RuntimeException failed) {
            spec.commandLine().getErr().println("replay failed: " + failed.getMessage());
            return 2;
        }
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
