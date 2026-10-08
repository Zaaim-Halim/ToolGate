package io.toolgate.validate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether a file path argument may be used, by its real path on this machine.
 *
 * <p>Comparing text is not enough: {@code /data/reports/link/key.txt} is inside {@code /data/reports}
 * as text but outside once the symlink {@code link} is followed, and on a case-insensitive volume
 * {@code /data/reports/SECRET} is the folder {@code secret}. So both the argument and every root are
 * resolved to real paths, which follow symlinks and carry the case stored on disk, and compared as
 * {@link Path} objects, so {@code /data/reports-old} is not inside {@code /data/reports}.
 *
 * <p>This reads file metadata, so it is blocking I/O, and its answer can change before the tool
 * opens the file. SafeFiles re-checks at open time.
 */
public final class PathCheck {

    private static final String UNIX_LINK_COUNT = "unix:nlink";

    private PathCheck() {}

    /**
     * What a policy allows for one path argument.
     *
     * @param under    roots the path must be inside; empty allows any path not excluded
     * @param notUnder roots the path must not be inside
     */
    public record Rule(List<Path> under, List<Path> notUnder) {

        /** Requires absolute, normalized roots and at least one of them. */
        public Rule {
            under = List.copyOf(under);
            notUnder = List.copyOf(notUnder);
            if (under.isEmpty() && notUnder.isEmpty()) {
                throw new IllegalArgumentException("a path rule needs under or notUnder roots");
            }
            for (Path root : under) {
                requireRoot(root);
            }
            for (Path root : notUnder) {
                requireRoot(root);
            }
        }

        private static void requireRoot(Path root) {
            if (!root.isAbsolute() || !root.equals(root.normalize())) {
                throw new IllegalArgumentException("root '" + root + "' must be absolute, without '.' or '..'");
            }
        }
    }

    /** The outcome of a check. */
    public sealed interface Result permits Accepted, Rejected {}

    /**
     * The path may be used.
     *
     * @param realPath where it really points: an existing file's real path, or for a new file the
     *                 real path of its folder plus its name
     */
    public record Accepted(Path realPath) implements Result {
        public Accepted {
            Objects.requireNonNull(realPath, "realPath");
        }
    }

    /**
     * The path must not be used.
     *
     * @param reason why, for the audit trail
     */
    public record Rejected(String reason) implements Result {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Checks one path against one rule.
     *
     * <p>A relative path is resolved against the rule's single {@code under} root, and the tool must
     * resolve it the same way; with several roots, or none, it is rejected because ToolGate cannot
     * know which folder the tool means.
     *
     * @param raw  the argument, exactly as the model wrote it
     * @param rule what the policy allows
     * @return {@link Accepted} with the real path, or {@link Rejected} with the reason
     */
    public static Result check(String raw, Rule rule) {
        Optional<String> badText = badText(raw);
        if (badText.isPresent()) {
            return new Rejected(badText.get());
        }
        Path path;
        try {
            path = Path.of(raw);
        } catch (InvalidPathException invalid) {
            return new Rejected("not a valid path: " + invalid.getReason());
        }
        if (!path.isAbsolute()) {
            if (rule.under().size() != 1) {
                return new Rejected("relative path '" + raw + "' needs exactly one under root to resolve against");
            }
            path = rule.under().getFirst().resolve(path);
        }
        return switch (realPath(path)) {
            case Rejected rejected -> rejected;
            case Accepted(Path real) -> checkRealPath(real, rule);
        };
    }

    private static Result checkRealPath(Path real, Rule rule) {
        Optional<String> hardLink = hardLinkProblem(real);
        if (hardLink.isPresent()) {
            return new Rejected(hardLink.get());
        }
        for (Path root : rule.notUnder()) {
            // A missing excluded root contains nothing that exists, and the parent of a new file exists.
            Optional<Path> realRoot = realRoot(root);
            if (realRoot.isPresent() && real.startsWith(realRoot.get())) {
                return new Rejected("'" + real + "' is under excluded root " + root);
            }
        }
        if (rule.under().isEmpty()) {
            return new Accepted(real);
        }
        for (Path root : rule.under()) {
            Optional<Path> realRoot = realRoot(root);
            if (realRoot.isPresent() && real.startsWith(realRoot.get())) {
                return new Accepted(real);
            }
        }
        return new Rejected("'" + real + "' is not under " + rule.under());
    }

    /** The real path of an existing file, or of a new file's existing parent folder plus its name. */
    private static Result realPath(Path path) {
        try {
            return new Accepted(path.toRealPath());
        } catch (NoSuchFileException missing) {
            return newFileRealPath(path);
        } catch (IOException | SecurityException unreadable) {
            return new Rejected("cannot resolve '" + path + "': " + unreadable);
        }
    }

    private static Result newFileRealPath(Path path) {
        Path name = path.getFileName();
        Path parent = path.getParent();
        if (name == null || parent == null || name.toString().equals("..") || name.toString().equals(".")) {
            return new Rejected("'" + path + "' does not exist and names no file");
        }
        if (Files.isSymbolicLink(path)) {
            // A dangling symlink: writing to it creates its target, which may be anywhere.
            return new Rejected("'" + path + "' is a symlink to a missing target");
        }
        try {
            return new Accepted(parent.toRealPath().resolve(name));
        } catch (NoSuchFileException missing) {
            return new Rejected("folder '" + parent + "' does not exist");
        } catch (IOException | SecurityException unreadable) {
            return new Rejected("cannot resolve '" + parent + "': " + unreadable);
        }
    }

    private static Optional<Path> realRoot(Path root) {
        try {
            return Optional.of(root.toRealPath());
        } catch (IOException | SecurityException missing) {
            return Optional.empty();
        }
    }

    /**
     * A file with several hard links may be the same file as one outside the root, which no path
     * comparison can see. File systems without a link count, such as on Windows, are not checked.
     */
    private static Optional<String> hardLinkProblem(Path real) {
        try {
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Object links = Files.getAttribute(real, UNIX_LINK_COUNT, LinkOption.NOFOLLOW_LINKS);
            return links instanceof Integer count && count > 1
                    ? Optional.of("'" + real + "' has " + count + " hard links, so it may be a file from elsewhere")
                    : Optional.empty();
        } catch (UnsupportedOperationException | IllegalArgumentException noLinkCount) {
            return Optional.empty();
        } catch (IOException | SecurityException unreadable) {
            return Optional.of("cannot read link count of '" + real + "': " + unreadable);
        }
    }

    /**
     * Rejects text that some tool or library might expand or decode into another path: control
     * characters, backslashes (Windows separators), percent-encoding, a leading {@code ~} (home
     * folder), and {@code file:} URIs.
     */
    private static Optional<String> badText(String raw) {
        if (raw.isEmpty()) {
            return Optional.of("empty path");
        }
        for (int index = 0; index < raw.length(); index++) {
            char c = raw.charAt(index);
            if (c < ' ' || c == 0x7f) {
                return Optional.of(String.format("control character U+%04X at position %d", (int) c, index));
            }
            if (c == '\\' || c == '%') {
                return Optional.of("'" + c + "' at position " + index + " may be decoded into another path");
            }
        }
        if (raw.startsWith("~")) {
            return Optional.of("a leading '~' may be expanded to a home folder");
        }
        if (raw.regionMatches(true, 0, "file:", 0, "file:".length())) {
            return Optional.of("file: URIs are not paths");
        }
        return Optional.empty();
    }
}
