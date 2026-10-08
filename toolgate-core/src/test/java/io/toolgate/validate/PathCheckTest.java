package io.toolgate.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.toolgate.validate.PathCheck.Accepted;
import io.toolgate.validate.PathCheck.Rejected;
import io.toolgate.validate.PathCheck.Result;
import io.toolgate.validate.PathCheck.Rule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs against a real temporary folder. On macOS that folder is under /var, itself a symlink to
 * /private/var, so every test also checks that roots are compared by their real paths.
 */
class PathCheckTest {

    @TempDir
    Path base;

    private Path reports;
    private Path outside;
    private Rule underReports;

    @BeforeEach
    void layOutFolders() throws IOException {
        reports = Files.createDirectories(base.resolve("data/reports"));
        Files.createDirectories(base.resolve("data/reports-old"));
        Files.createDirectories(reports.resolve("secret"));
        outside = Files.createDirectories(base.resolve("outside"));
        Files.writeString(reports.resolve("q3.csv"), "q3");
        Files.writeString(reports.resolve("secret/salaries.csv"), "s");
        Files.writeString(outside.resolve("key.txt"), "k");
        Files.writeString(base.resolve("data/reports-old/x.csv"), "x");
        underReports = new Rule(List.of(reports), List.of());
    }

    @Test
    void acceptsFileInsideRoot() throws IOException {
        assertThat(check(reports.resolve("q3.csv"), underReports))
                .isEqualTo(new Accepted(reports.resolve("q3.csv").toRealPath()));
    }

    @Test
    void acceptsTheRootItself() {
        assertThat(check(reports, underReports)).isInstanceOf(Accepted.class);
    }

    @Test
    void rejectsDotDotEscape() {
        assertThat(PathCheck.check(reports + "/../../outside/key.txt", underReports)).isInstanceOf(Rejected.class);
    }

    @Test
    void rejectsSymlinkEscape() throws IOException {
        Files.createSymbolicLink(reports.resolve("link"), outside);

        assertThat(check(reports.resolve("link/key.txt"), underReports)).isInstanceOfSatisfying(Rejected.class,
                rejected -> assertThat(rejected.reason()).contains("is not under"));
    }

    @Test
    void acceptsSymlinkThatStaysInside() throws IOException {
        Files.createSymbolicLink(reports.resolve("latest.csv"), reports.resolve("q3.csv"));

        assertThat(check(reports.resolve("latest.csv"), underReports)).isInstanceOf(Accepted.class);
    }

    @Test
    void rejectsSiblingFolderWithSharedPrefix() {
        assertThat(check(base.resolve("data/reports-old/x.csv"), underReports)).isInstanceOf(Rejected.class);
    }

    @Test
    void acceptsNewFileInExistingFolder() throws IOException {
        assertThat(check(reports.resolve("q4.csv"), underReports))
                .isEqualTo(new Accepted(reports.toRealPath().resolve("q4.csv")));
    }

    @Test
    void rejectsNewFileInMissingFolder() {
        assertThat(check(reports.resolve("2027/q1.csv"), underReports)).isInstanceOfSatisfying(Rejected.class,
                rejected -> assertThat(rejected.reason()).contains("does not exist"));
    }

    /** Writing through a dangling symlink creates its target, wherever that is. */
    @Test
    void rejectsDanglingSymlink() throws IOException {
        Files.createSymbolicLink(reports.resolve("drop.csv"), outside.resolve("created-later.csv"));

        assertThat(check(reports.resolve("drop.csv"), underReports)).isInstanceOfSatisfying(Rejected.class,
                rejected -> assertThat(rejected.reason()).contains("symlink to a missing target"));
    }

    @Test
    void rejectsFileWithSeveralHardLinks() throws IOException {
        try {
            Files.createLink(reports.resolve("innocent.csv"), outside.resolve("key.txt"));
        } catch (UnsupportedOperationException noHardLinks) {
            assumeTrue(false, "file system has no hard links");
        }

        assertThat(check(reports.resolve("innocent.csv"), underReports)).isInstanceOfSatisfying(Rejected.class,
                rejected -> assertThat(rejected.reason()).contains("2 hard links"));
    }

    @Test
    void rejectsSymlinkLoop() throws IOException {
        Files.createSymbolicLink(reports.resolve("loop-a"), reports.resolve("loop-b"));
        Files.createSymbolicLink(reports.resolve("loop-b"), reports.resolve("loop-a"));

        assertThat(check(reports.resolve("loop-a/x.csv"), underReports)).isInstanceOf(Rejected.class);
    }

    @Test
    void notUnderExcludesAFolderInsideTheRoot() {
        var rule = new Rule(List.of(reports), List.of(reports.resolve("secret")));

        assertThat(check(reports.resolve("secret/salaries.csv"), rule)).isInstanceOf(Rejected.class);
        assertThat(check(reports.resolve("q3.csv"), rule)).isInstanceOf(Accepted.class);
    }

    @Test
    void notUnderCannotBeBypassedThroughASymlink() throws IOException {
        Files.createSymbolicLink(reports.resolve("public"), reports.resolve("secret"));
        var rule = new Rule(List.of(reports), List.of(reports.resolve("secret")));

        assertThat(check(reports.resolve("public/salaries.csv"), rule)).isInstanceOf(Rejected.class);
    }

    /** On macOS's default case-insensitive volumes, SECRET is the folder secret. */
    @Test
    void notUnderCannotBeBypassedByCase() {
        assumeTrue(Files.exists(reports.resolve("SECRET")), "file system is case-sensitive");
        var rule = new Rule(List.of(reports), List.of(reports.resolve("secret")));

        assertThat(check(reports.resolve("SECRET/salaries.csv"), rule)).isInstanceOf(Rejected.class);
    }

    @Test
    void notUnderAloneAllowsEverythingElse() {
        var rule = new Rule(List.of(), List.of(reports.resolve("secret")));

        assertThat(check(outside.resolve("key.txt"), rule)).isInstanceOf(Accepted.class);
        assertThat(check(reports.resolve("secret/salaries.csv"), rule)).isInstanceOf(Rejected.class);
    }

    @Test
    void rootGivenThroughASymlinkStillMatches() throws IOException {
        Path alias = Files.createSymbolicLink(base.resolve("reports-alias"), reports);
        var rule = new Rule(List.of(alias), List.of());

        assertThat(check(reports.resolve("q3.csv"), rule)).isInstanceOf(Accepted.class);
        assertThat(check(alias.resolve("q3.csv"), rule)).isInstanceOf(Accepted.class);
    }

    @Test
    void rejectsWhenTheRootIsMissing() {
        var rule = new Rule(List.of(base.resolve("not-created")), List.of());

        assertThat(check(base.resolve("not-created/x.csv"), rule)).isInstanceOf(Rejected.class);
    }

    @Test
    void relativePathResolvesAgainstTheSingleRoot() throws IOException {
        assertThat(PathCheck.check("q3.csv", underReports))
                .isEqualTo(new Accepted(reports.resolve("q3.csv").toRealPath()));
        assertThat(PathCheck.check("secret/salaries.csv", underReports)).isInstanceOf(Accepted.class);
        assertThat(PathCheck.check("../reports-old/x.csv", underReports)).isInstanceOf(Rejected.class);
        assertThat(PathCheck.check("../../outside/key.txt", underReports)).isInstanceOf(Rejected.class);
    }

    @Test
    void relativePathIsRejectedWithoutExactlyOneRoot() {
        var twoRoots = new Rule(List.of(reports, outside), List.of());
        var noRoot = new Rule(List.of(), List.of(reports.resolve("secret")));

        assertThat(PathCheck.check("q3.csv", twoRoots)).isInstanceOfSatisfying(Rejected.class,
                rejected -> assertThat(rejected.reason()).contains("exactly one under root"));
        assertThat(PathCheck.check("q3.csv", noRoot)).isInstanceOf(Rejected.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "~/q3.csv", "~root/.ssh/id_rsa", "file:///etc/passwd", "FILE:/etc/passwd", "q3.csv\u0000.txt",
        "q3\n.csv", "..\\..\\outside\\key.txt", "%2e%2e/%2e%2e/outside/key.txt", "q3.csv\u007f",
    })
    void rejectsTextThatMayBeExpandedOrDecoded(String raw) {
        assertThat(PathCheck.check(raw, underReports)).isInstanceOf(Rejected.class);
    }

    @Test
    void ruleRequiresAbsoluteNormalizedRoots() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Rule(List.of(Path.of("data")), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Rule(List.of(Path.of("/data/../etc")), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new Rule(List.of(), List.of()));
    }

    /** Whatever mix of segments the model writes, nothing accepted lies outside the root. */
    @Test
    void noRelativeCombinationEscapes() throws IOException {
        Files.createSymbolicLink(reports.resolve("link"), outside);
        Path realRoot = reports.toRealPath();
        List<String> segments = List.of("..", ".", "link", "secret", "q3.csv", "reports", "data", "x");
        for (String first : segments) {
            for (String second : segments) {
                for (String third : segments) {
                    String raw = first + "/" + second + "/" + third;
                    Result result = PathCheck.check(raw, underReports);
                    if (result instanceof Accepted(Path real)) {
                        // Path.startsWith, not AssertJ's, which would resolve a new file on disk.
                        assertThat(real.startsWith(realRoot)).as(raw + " -> " + real).isTrue();
                    }
                }
            }
        }
    }

    private static Result check(Path path, Rule rule) {
        return PathCheck.check(path.toString(), rule);
    }
}
