package io.toolgate.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** Case C's tools: they read any path and fetch any URL they are given, so ToolGate must not. */
@Component
public class OpsTools {

    @Tool(description = "Read an application log file")
    String readLog(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Tool(description = "Fetch a status page over HTTP")
    String fetch(String url) {
        // Stands in for an HTTP client; what matters is which URLs reach it.
        return "{\"status\":\"operational\",\"url\":\"" + url + "\"}";
    }
}
