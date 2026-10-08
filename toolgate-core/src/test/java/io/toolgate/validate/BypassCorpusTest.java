package io.toolgate.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs every case in {@code bypass/corpus-v1.txt}: known tricks that must stay denied, and
 * near-misses that must stay allowed. Each case is its own test, named by its line.
 */
class BypassCorpusTest {

    private static final String CORPUS = "/bypass/corpus-v1.txt";
    private static final int MINIMUM_CASES = 100;
    private static final Set<String> KINDS = Set.of("url", "url-hosts", "path");

    private static final UrlCheck.Rule ANY_PUBLIC_HOST = UrlCheck.Rule.DEFAULT;
    private static final UrlCheck.Rule MYCOMPANY = new UrlCheck.Rule(Set.of("https"),
            List.of(HostPattern.parse("mycompany.com"), HostPattern.parse("*.mycompany.com")), Set.of());

    @TempDir
    Path base;

    /** One line of the corpus. */
    record Case(int line, String kind, boolean allow, String input, String why) {}

    @TestFactory
    Stream<DynamicTest> everyCaseHolds() throws IOException {
        Path reports = layOutPathFixture();
        var pathRule = new PathCheck.Rule(List.of(reports), List.of(reports.resolve("secret")));
        return load().stream().map(testCase -> DynamicTest.dynamicTest(
                "line " + testCase.line() + ": " + testCase.kind() + " " + (testCase.allow() ? "allow" : "deny")
                        + " " + printable(testCase.input()) + " (" + testCase.why() + ")",
                () -> assertThat(allowed(testCase, pathRule, reports))
                        .as("line %d: %s", testCase.line(), testCase.why())
                        .isEqualTo(testCase.allow())));
    }

    @Test
    void corpusIsLargeEnoughAndWellFormed() throws IOException {
        List<Case> cases = load();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(MINIMUM_CASES);
        assertThat(cases).allSatisfy(testCase -> {
            assertThat(KINDS).contains(testCase.kind());
            assertThat(testCase.why()).as("line %d needs a reason", testCase.line()).isNotBlank();
        });
        assertThat(cases).filteredOn(testCase -> !testCase.allow()).hasSizeGreaterThan(cases.size() / 2);
    }

    @Test
    void corpusFileIsAscii() throws IOException {
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(CORPUS), CORPUS)) {
            for (byte b : in.readAllBytes()) {
                assertThat(b).as("non-ASCII byte; write \\uXXXX instead").isBetween((byte) 0, (byte) 0x7f);
            }
        }
    }

    private boolean allowed(Case testCase, PathCheck.Rule pathRule, Path reports) {
        return switch (testCase.kind()) {
            case "url" -> UrlCheck.check(testCase.input(), ANY_PUBLIC_HOST) instanceof UrlCheck.Accepted;
            case "url-hosts" -> UrlCheck.check(testCase.input(), MYCOMPANY) instanceof UrlCheck.Accepted;
            case "path" -> PathCheck.check(testCase.input()
                    .replace("$R", reports.toString())
                    .replace("$B", base.toString()), pathRule) instanceof PathCheck.Accepted;
            default -> throw new IllegalArgumentException("unknown kind " + testCase.kind());
        };
    }

    private Path layOutPathFixture() throws IOException {
        Path reports = Files.createDirectories(base.resolve("data/reports"));
        Path outside = Files.createDirectories(base.resolve("outside"));
        Files.createDirectories(reports.resolve("secret"));
        Files.createDirectories(base.resolve("data/reports-old"));
        Files.writeString(reports.resolve("q3.csv"), "q3");
        Files.writeString(reports.resolve("secret/salaries.csv"), "s");
        Files.writeString(outside.resolve("key.txt"), "k");
        Files.writeString(base.resolve("data/reports-old/x.csv"), "x");
        Files.createSymbolicLink(reports.resolve("link"), outside);
        Files.createSymbolicLink(reports.resolve("latest.csv"), reports.resolve("q3.csv"));
        Files.createSymbolicLink(reports.resolve("drop.csv"), outside.resolve("missing.csv"));
        Files.createLink(reports.resolve("shared.csv"), outside.resolve("key.txt"));
        return reports;
    }

    private static List<Case> load() throws IOException {
        String text;
        try (InputStream in = Objects.requireNonNull(BypassCorpusTest.class.getResourceAsStream(CORPUS), CORPUS)) {
            text = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
        var cases = new ArrayList<Case>();
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\|", -1);
            if (fields.length != 4) {
                throw new IllegalStateException("line " + (index + 1) + " needs 4 fields: " + line);
            }
            String expect = fields[1].strip();
            if (!expect.equals("allow") && !expect.equals("deny")) {
                throw new IllegalStateException("line " + (index + 1) + ": expect must be allow or deny");
            }
            cases.add(new Case(index + 1, fields[0].strip(), expect.equals("allow"),
                    unescape(fields[2].strip()), fields[3].strip()));
        }
        return cases;
    }

    /** Decodes {@code \\uXXXX}; the corpus has no other escapes. */
    private static String unescape(String field) {
        var out = new StringBuilder();
        for (int index = 0; index < field.length(); index++) {
            if (field.startsWith("\\u", index)) {
                out.append((char) Integer.parseInt(field.substring(index + 2, index + 6), 16));
                index += 5;
            } else {
                out.append(field.charAt(index));
            }
        }
        return out.toString();
    }

    private static String printable(String input) {
        var out = new StringBuilder();
        input.chars().forEach(c -> out.append(c >= 0x20 && c < 0x7f ? String.valueOf((char) c) : String.format("\\u%04x", c)));
        return out.isEmpty() ? "(empty)" : out.toString();
    }
}
