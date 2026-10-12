package io.toolgate.cli;

import io.toolgate.api.PolicyReplay;
import io.toolgate.api.ReplayReport;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.spi.OwnershipResolver;
import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.ToolCatalog;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Replays the tool calls a JDBC session store recorded through a proposed policy, and reports which
 * decisions would change.
 */
@Command(name = "replay", mixinStandardHelpOptions = true,
        description = "Replay recorded tool calls through a proposed policy and report what would change.")
final class ReplayCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    History history;

    @Option(names = "--policy-class", required = true,
            description = "The proposed policy: a class implementing Supplier<ToolGatePolicy>.")
    String policyClass;

    @Option(names = "--catalog-class",
            description = "Tool definitions, needed when the policy has argument rules: a class implementing Supplier<ToolCatalog>.")
    String catalogClass;

    @Option(names = "--owners-class",
            description = "Ownership lookups, needed for ownership rules: a class implementing Supplier<OwnershipResolver>.")
    String ownersClass;

    @Option(names = "--fail-on-change", description = "Exit with 1 if any decision would change, for CI.")
    boolean failOnChange;

    @Override
    public Integer call() {
        Instant to = Instant.now();
        Instant from;
        try {
            from = history.from(to);
        } catch (IllegalArgumentException badDuration) {
            spec.commandLine().getErr().println("invalid --since: " + badDuration.getMessage());
            return 2;
        }
        try (var classes = new Classes(history.classpath)) {
            ToolGatePolicy policy = classes.supplied(policyClass, ToolGatePolicy.class);
            PolicyReplay.Builder replay = PolicyReplay.builder(policy);
            if (catalogClass != null) {
                replay.toolCatalog(classes.supplied(catalogClass, ToolCatalog.class));
            }
            if (ownersClass != null) {
                replay.ownershipResolver(classes.supplied(ownersClass, OwnershipResolver.class));
            }
            ReplayReport report;
            try (Stream<SessionEvent> events = history.events(classes, from, to)) {
                report = replay.build().replay(events);
            }
            spec.commandLine().getOut().println(report.summary());
            return failOnChange && !report.changes().isEmpty() ? 1 : 0;
        } catch (RuntimeException failed) {
            spec.commandLine().getErr().println("replay failed: " + failed.getMessage());
            return 2;
        }
    }
}
