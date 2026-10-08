package io.toolgate.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.toolgate.validate.PathCheck;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

class PathCheckFuzzTest {

    private static Path base;
    private static Path realRoot;
    private static Path realSecret;
    private static PathCheck.Rule rule;

    /** A fixture with every escape the corpus knows: symlinks out, a dangling link, a hard link, a sibling. */
    @BeforeAll
    static void layOutFixture() throws IOException {
        base = Files.createTempDirectory("toolgate-fuzz");
        Path reports = Files.createDirectories(base.resolve("data/reports"));
        Path outside = Files.createDirectories(base.resolve("outside"));
        Files.createDirectories(reports.resolve("secret/deeper"));
        Files.createDirectories(base.resolve("data/reports-old"));
        Files.writeString(reports.resolve("q3.csv"), "q3");
        Files.writeString(reports.resolve("secret/salaries.csv"), "s");
        Files.writeString(outside.resolve("key.txt"), "k");
        Files.createSymbolicLink(reports.resolve("link"), outside);
        Files.createSymbolicLink(reports.resolve("up"), base);
        Files.createSymbolicLink(reports.resolve("public"), reports.resolve("secret"));
        Files.createSymbolicLink(reports.resolve("drop.csv"), outside.resolve("missing.csv"));
        Files.createLink(reports.resolve("shared.csv"), outside.resolve("key.txt"));
        realRoot = reports.toRealPath();
        realSecret = reports.resolve("secret").toRealPath();
        rule = new PathCheck.Rule(List.of(reports), List.of(reports.resolve("secret")));
    }

    @AfterAll
    static void removeFixture() throws IOException {
        try (var paths = Files.walk(base)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Whatever the model writes, relative or absolute: nothing accepted leaves the root or enters secret. */
    @FuzzTest
    void neverAcceptsAPathOutsideTheRoot(FuzzedDataProvider data) {
        String raw = data.consumeRemainingAsString().replace("$R", realRoot.toString());

        if (PathCheck.check(raw, rule) instanceof PathCheck.Accepted(Path real)) {
            assertThat(real.startsWith(realRoot)).as("%s -> %s", raw, real).isTrue();
            assertThat(real.startsWith(realSecret)).as("%s -> %s", raw, real).isFalse();
            assertThat(Files.isSymbolicLink(real)).as("%s -> %s", raw, real).isFalse();
        }
    }
}
