package io.toolgate.demo;

import io.toolgate.spi.AuditEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * What a real model tried in each case and what ToolGate decided, appended to
 * {@code target/real-model-spring-ai.md}: the model's choices vary, so they are reported, not asserted.
 */
final class RealModelReport {

    static final String MODEL = System.getenv().getOrDefault("TOOLGATE_REAL_MODEL", "");
    static final String URL = System.getenv().getOrDefault("TOOLGATE_REAL_MODEL_URL", "http://localhost:11434/v1");
    private static final Path FILE = Path.of("target", "real-model-spring-ai.md");

    private RealModelReport() {}

    static void section(String title, String prompt, List<AuditEvent> audit, List<String> ran, String outcome) {
        var text = new StringBuilder("\n## ").append(title).append("\n\nModel `").append(MODEL).append("`. Prompt: ")
                .append(prompt).append("\n\n| # | Tool | Decision | Rule |\n| --- | --- | --- | --- |\n");
        int row = 0;
        for (AuditEvent event : audit) {
            if (event instanceof AuditEvent.Decided decided) {
                text.append("| ").append(++row).append(" | `").append(decided.tool()).append("` | ")
                        .append(decided.applied().getClass().getSimpleName()).append(" | `").append(decided.applied().ruleId())
                        .append("` |\n");
            }
        }
        if (row == 0) {
            text.append("| - | the model called no tool | | |\n");
        }
        text.append("\nTools that ran: ").append(ran.isEmpty() ? "none" : "`" + String.join("`, `", ran) + "`")
                .append("\n\nOutcome: ").append(outcome.replace('\n', ' ')).append('\n');
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
