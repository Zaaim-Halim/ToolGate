package io.toolgate.api.policy;

/**
 * How a review screen shows an argument it must not show in full.
 *
 * @param visibleTail how many trailing characters stay readable; 0 hides them all
 */
public record Mask(int visibleTail) {

    private static final String HIDDEN = "••••";

    /** Rejects a negative tail. */
    public Mask {
        if (visibleTail < 0) {
            throw new InvalidPolicyException("a mask's visible tail must not be negative");
        }
    }

    /**
     * Shows none of the value.
     *
     * @return the mask
     */
    public static Mask full() {
        return new Mask(0);
    }

    /**
     * Shows only the last characters, such as the last 4 of an IBAN.
     *
     * @param characters how many
     * @return the mask
     */
    public static Mask last(int characters) {
        return new Mask(characters);
    }

    /**
     * Masks a value. A value no longer than the visible tail is hidden entirely, or the mask would show it all.
     *
     * @param value the value as text
     * @return the masked text
     */
    public String apply(String value) {
        if (visibleTail == 0 || value.codePointCount(0, value.length()) <= visibleTail) {
            return HIDDEN;
        }
        int start = value.offsetByCodePoints(value.length(), -visibleTail);
        return HIDDEN + " " + value.substring(start);
    }
}
