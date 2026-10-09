package io.toolgate.email;

import java.util.Locale;

/**
 * What an allowed value must be, written without Jakarta Mail: comma-separated pieces, each a plain
 * address or {@code name <address>}, with exactly one {@code @}, at mycompany.com or a subdomain.
 */
final class EmailInvariant {

    private EmailInvariant() {}

    static boolean holds(String text) {
        if (!text.chars().allMatch(c -> c >= 0x20 && c != 0x7f)) {
            return false;
        }
        for (String piece : text.split(",", -1)) {
            String address = piece.strip();
            int open = address.indexOf('<');
            if (open >= 0) {
                if (!address.endsWith(">") || address.substring(0, open).contains("@")) {
                    return false;
                }
                address = address.substring(open + 1, address.length() - 1).strip();
            }
            int at = address.indexOf('@');
            if (at <= 0 || at != address.lastIndexOf('@')) {
                return false;
            }
            String domain = address.substring(at + 1).toLowerCase(Locale.ROOT);
            if (!address.chars().allMatch(c -> c > 0x20 && c < 0x7f)
                    || !(domain.equals("mycompany.com") || domain.endsWith(".mycompany.com"))) {
                return false;
            }
        }
        return true;
    }
}
