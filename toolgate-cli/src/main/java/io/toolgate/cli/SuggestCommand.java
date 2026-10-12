package io.toolgate.cli;

import io.toolgate.api.PolicySuggestion;
import io.toolgate.api.SuggestedPolicy;
import io.toolgate.spi.SessionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Drafts a policy from the tool calls a JDBC session store recorded, as Java source to review. */
@Command(name = "suggest", mixinStandardHelpOptions = true,
        description = "Draft a policy from recorded tool calls, as a Java class to review.")
final class SuggestCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Mixin
    History history;

    @Option(names = "--min-sessions", defaultValue = "3",
            description = "Sessions a tool, host, email domain or path root must appear in to be suggested;"
                    + " rarer ones are listed for review. Default: ${DEFAULT-VALUE}.")
    int minSessions;

    @Option(names = "--class-name", defaultValue = "SuggestedPolicy",
            description = "The generated class, with its package if any. Default: ${DEFAULT-VALUE}.")
    String className;

    @Option(names = {"-o", "--output"}, description = "Write the class to this file instead of standard output.")
    Path output;

    @Override
    public Integer call() {
        Instant to = Instant.now();
        Instant from;
        PolicySuggestion suggestion;
        try {
            from = history.from(to);
            suggestion = PolicySuggestion.builder().minSessions(minSessions).className(className).build();
        } catch (IllegalArgumentException badOption) {
            spec.commandLine().getErr().println("invalid option: " + badOption.getMessage());
            return 2;
        }
        try (var classes = new Classes(history.classpath)) {
            SuggestedPolicy draft;
            try (Stream<SessionEvent> events = history.events(classes, from, to)) {
                draft = suggestion.suggest(events);
            }
            if (draft.calls() == 0) {
                spec.commandLine().getErr().println("no tool calls were recorded since " + from);
                return 1;
            }
            if (output == null) {
                spec.commandLine().getOut().print(draft.javaSource());
            } else {
                Files.writeString(output, draft.javaSource());
                spec.commandLine().getOut().println("Wrote " + output);
            }
            spec.commandLine().getErr().print(draft.summary());
            return 0;
        } catch (RuntimeException | IOException failed) {
            spec.commandLine().getErr().println("suggest failed: " + failed.getMessage());
            return 2;
        }
    }
}
