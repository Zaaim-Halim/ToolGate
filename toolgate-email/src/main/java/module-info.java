/**
 * The {@code emailDomains} rule: recipients parsed with Jakarta Mail, every one checked, and every
 * address form that mail libraries read differently denied.
 */
module io.toolgate.email {
    requires transitive io.toolgate.core;
    requires jakarta.mail;

    exports io.toolgate.email;
}
