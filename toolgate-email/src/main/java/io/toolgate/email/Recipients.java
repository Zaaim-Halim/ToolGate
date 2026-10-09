package io.toolgate.email;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the recipients in one string, as strictly as possible.
 *
 * <p>Jakarta Mail splits lists and reads display names and angle brackets. Everything RFC 5322 allows
 * beyond plain {@code local@domain} and simple display names is then denied: quoted local parts
 * ({@code "a@evil.example"@mycompany.com}), escapes, comments, groups, source routes, IP-literal
 * domains, encoded words, semicolon lists and Unicode domains. Mail libraries disagree on those forms,
 * so one could make ToolGate check one recipient while the mailer sends to another.
 */
final class Recipients {

    static final int MAX_LENGTH = 8_192;
    static final int MAX_RECIPIENTS = 100;
    private static final int MAX_LOCAL_PART = 64;

    /** The forms beyond plain addresses and simple names, and why each is denied. */
    private static final List<Forbidden> FORBIDDEN = List.of(
            new Forbidden("\"", "quoted text, which can hide an @ in a local part or a display name"),
            new Forbidden("\\", "escapes"),
            new Forbidden("(", "comments"),
            new Forbidden(")", "comments"),
            new Forbidden("[", "IP-literal domains"),
            new Forbidden("]", "IP-literal domains"),
            new Forbidden(":", "groups and source routes"),
            new Forbidden(";", "semicolons; separate recipients with commas"),
            new Forbidden("=?", "encoded words"));

    private static final Pattern DOT_ATOM = Pattern.compile(
            "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*");
    private static final Pattern SIMPLE_NAME = Pattern.compile("[\\p{L}\\p{M}\\p{N} .'-]+");

    private Recipients() {}

    /** Every recipient in the text, with its lowercase domain; or why the text is denied. */
    sealed interface Result {
    }

    record Read(List<String> domains) implements Result {
    }

    record Denied(String reason) implements Result {
    }

    private record Forbidden(String text, String what) {
    }

    static Result read(String text) {
        if (text.length() > MAX_LENGTH) {
            return new Denied("longer than " + MAX_LENGTH + " characters");
        }
        if (text.codePoints().anyMatch(Recipients::isInvisible)) {
            return new Denied("contains control or invisible characters");
        }
        for (Forbidden forbidden : FORBIDDEN) {
            if (text.contains(forbidden.text())) {
                return new Denied("uses " + forbidden.what());
            }
        }
        // The accepted shape, stated before Jakarta Mail reads it: comma-separated pieces, each a bare
        // address or "name <address>". Jakarta Mail tolerates more, such as empty elements and text
        // after the brackets, which other mailers may reject or read differently.
        for (String piece : text.split(",", -1)) {
            Optional<String> problem = shapeProblem(piece.strip());
            if (problem.isPresent()) {
                return new Denied(problem.get());
            }
        }
        InternetAddress[] parsed;
        try {
            parsed = InternetAddress.parse(text, true);
        } catch (AddressException malformed) {
            return new Denied("is not a list of email addresses");
        }
        if (parsed.length == 0) {
            return new Denied("names no recipient");
        }
        if (parsed.length > MAX_RECIPIENTS) {
            return new Denied("names more than " + MAX_RECIPIENTS + " recipients");
        }
        var domains = new ArrayList<String>();
        for (InternetAddress address : parsed) {
            Optional<String> problem = problem(address);
            if (problem.isPresent()) {
                return new Denied(problem.get());
            }
            String addr = address.getAddress();
            domains.add(addr.substring(addr.indexOf('@') + 1).toLowerCase(Locale.ROOT));
        }
        // Each accepted recipient holds exactly one @ and a display name none, so a count that differs
        // means the parser read the text in a way this check did not foresee.
        if (text.chars().filter(c -> c == '@').count() != domains.size()) {
            return new Denied("could not be read unambiguously");
        }
        return new Read(List.copyOf(domains));
    }

    private static Optional<String> shapeProblem(String piece) {
        if (piece.isEmpty()) {
            return Optional.of("has an empty recipient; check for a stray comma");
        }
        long opening = piece.chars().filter(c -> c == '<').count();
        long closing = piece.chars().filter(c -> c == '>').count();
        if (opening == 0 && closing == 0) {
            return Optional.empty();
        }
        if (opening != 1 || closing != 1 || !piece.endsWith(">") || piece.indexOf('<') > piece.indexOf('>')) {
            return Optional.of("must each read address or name <address>");
        }
        return Optional.empty();
    }

    private static Optional<String> problem(InternetAddress address) {
        if (address.isGroup()) {
            return Optional.of("uses groups");
        }
        String personal = address.getPersonal();
        if (personal != null && !SIMPLE_NAME.matcher(personal).matches()) {
            return Optional.of("has a display name with characters other than letters, digits, spaces and . - '");
        }
        String addr = address.getAddress();
        int at = addr.indexOf('@');
        if (at <= 0 || at != addr.lastIndexOf('@')) {
            return Optional.of("'" + addr + "' is not local@domain");
        }
        String local = addr.substring(0, at);
        String domain = addr.substring(at + 1).toLowerCase(Locale.ROOT);
        if (local.length() > MAX_LOCAL_PART || !DOT_ATOM.matcher(local).matches()) {
            return Optional.of("'" + addr + "' has an unusual local part");
        }
        if (!DomainPattern.isDomain(domain)) {
            return Optional.of("'" + addr + "' has an invalid domain; Unicode domains must be written in their xn-- form");
        }
        return Optional.empty();
    }

    private static boolean isInvisible(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE
                || type == Character.UNASSIGNED || type == Character.PRIVATE_USE;
    }
}
