package io.toolgate.api;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What a tool looks like to the model: the description it reads and the arguments it may pass. A
 * description can carry instructions too, as in tool poisoning, so a pin covers both.
 *
 * <p>The pin is computed once, when the definition is created, because the gate compares it on every
 * call to a pinned tool.
 */
public final class ToolDefinition {

    private static final String PREFIX = "sha256:";
    private static final int SHA256_HEX_DIGITS = 64;
    /** Beyond this exponent ECMAScript writes a number in exponent form, as in {@code 1e+21}. */
    private static final int MAX_PLAIN_EXPONENT = 21;
    /** At or below this exponent ECMAScript writes a number in exponent form, as in {@code 1e-7}. */
    private static final int MIN_PLAIN_EXPONENT = -6;
    private static final MathContext ONE_DIGIT = new MathContext(1, RoundingMode.HALF_EVEN);
    private static final int MAX_NUMBER_IN_MESSAGE = 40;

    private final ToolId tool;
    private final String description;
    private final ObjectValue inputSchema;
    private final String canonicalJson;
    private final String sha256;

    /**
     * Creates a definition and computes its pin.
     *
     * @param tool        the tool
     * @param description the description the model reads; may be empty
     * @param inputSchema the JSON Schema of its arguments, as the framework or MCP server declares it
     * @throws IllegalArgumentException if a number is too large for a double or a text holds a lone
     *                                  surrogate, neither of which RFC 8785 can canonicalize
     */
    public ToolDefinition(ToolId tool, String description, ObjectValue inputSchema) {
        this.tool = Objects.requireNonNull(tool, "tool");
        this.description = Objects.requireNonNull(description, "description");
        this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
        var json = new StringBuilder();
        writeCanonical(json, ArgValue.object(Map.of(
                "description", ArgValue.text(description),
                "inputSchema", inputSchema,
                "tool", ArgValue.text(tool.toString()))));
        this.canonicalJson = json.toString();
        this.sha256 = PREFIX + HexFormat.of().formatHex(sha256(canonicalJson));
    }

    /**
     * Reads a definition from the JSON Schema text a framework or MCP server gives.
     *
     * @param tool        the tool
     * @param description the description
     * @param schemaJson  the input schema, a JSON object
     * @return the definition
     * @throws IllegalArgumentException if the schema is not a JSON object, or cannot be canonicalized
     */
    public static ToolDefinition of(ToolId tool, String description, String schemaJson) {
        if (!(ArgValueJson.read(schemaJson) instanceof ObjectValue schema)) {
            throw new IllegalArgumentException("an input schema must be a JSON object");
        }
        return new ToolDefinition(tool, description, schema);
    }

    /**
     * The tool.
     *
     * @return the tool
     */
    public ToolId tool() {
        return tool;
    }

    /**
     * The description the model reads.
     *
     * @return the description; may be empty
     */
    public String description() {
        return description;
    }

    /**
     * The JSON Schema of the tool's arguments.
     *
     * @return the schema
     */
    public ObjectValue inputSchema() {
        return inputSchema;
    }

    /**
     * The text the pin is computed from: the JSON object
     * {@code {"description": …, "inputSchema": …, "tool": …}}, with the tool written as in
     * {@code mcp:jira/search_issues}, canonicalized by RFC 8785 (JSON Canonicalization Scheme). Any
     * RFC 8785 library reproduces it, so a pin can be computed outside Java, for example in a review
     * script.
     *
     * @return the canonical JSON
     */
    public String canonicalJson() {
        return canonicalJson;
    }

    /**
     * The value to pin in a policy: SHA-256 over the UTF-8 bytes of {@link #canonicalJson()}, so
     * neither field order nor {@code 1.0} against {@code 1} changes it.
     *
     * @return {@code sha256:} and 64 lowercase hex digits
     */
    public String sha256() {
        return sha256;
    }

    /**
     * Whether a pin is well-formed.
     *
     * @param pin a value from {@link #sha256()}
     * @return {@code true} for {@code sha256:} and 64 lowercase hex digits
     */
    public static boolean isPin(String pin) {
        return pin.length() == PREFIX.length() + SHA256_HEX_DIGITS && pin.startsWith(PREFIX)
                && pin.substring(PREFIX.length()).chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }

    /** Equal when the tool, description and schema are; numbers compare as values, as in {@link NumberValue}. */
    @Override
    public boolean equals(Object other) {
        return other instanceof ToolDefinition that && tool.equals(that.tool) && description.equals(that.description)
                && inputSchema.equals(that.inputSchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tool, description, inputSchema);
    }

    @Override
    public String toString() {
        return "ToolDefinition[" + tool + ", " + sha256 + "]";
    }

    /** RFC 8785: members sorted by UTF-16 code units, which is {@link String#compareTo}, and no whitespace. */
    private static void writeCanonical(StringBuilder json, ArgValue value) {
        switch (value) {
            case ObjectValue object -> {
                json.append('{');
                String separator = "";
                for (var field : new TreeMap<>(object.fields()).entrySet()) {
                    json.append(separator);
                    writeString(json, field.getKey());
                    json.append(':');
                    writeCanonical(json, field.getValue());
                    separator = ",";
                }
                json.append('}');
            }
            case ListValue list -> {
                json.append('[');
                String separator = "";
                for (ArgValue element : list.elements()) {
                    json.append(separator);
                    writeCanonical(json, element);
                    separator = ",";
                }
                json.append(']');
            }
            case NumberValue(BigDecimal number) -> json.append(ecmaScriptNumber(number));
            case TextValue(String text) -> writeString(json, text);
            case BooleanValue(boolean bool) -> json.append(bool);
            case NullValue nothing -> json.append("null");
        }
    }

    /**
     * Escapes as ECMAScript's {@code JSON.stringify}: the quote, the backslash, short forms for
     * {@code \b \f \n \r \t}, a six-character escape with lowercase hex digits for other control characters, and everything
     * else as it is, including non-ASCII.
     */
    private static void writeString(StringBuilder json, String text) {
        json.append('"');
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (Character.isSurrogate(c)) {
                boolean paired = Character.isHighSurrogate(c) && index + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(index + 1));
                if (!paired) {
                    throw new IllegalArgumentException("text holds a lone surrogate, which RFC 8785 cannot canonicalize");
                }
                json.append(c).append(text.charAt(++index));
                continue;
            }
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < ' ') {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }

    /**
     * ECMAScript's {@code Number.prototype.toString} of the nearest double, as RFC 8785 requires: the
     * shortest digits that read back as the same double, plain between 1e-7 and 1e21, else in exponent form.
     */
    private static String ecmaScriptNumber(BigDecimal number) {
        double value = number.doubleValue();
        if (Double.isInfinite(value)) {
            throw new IllegalArgumentException("number " + abbreviated(number)
                    + " is too large for a double, which RFC 8785 cannot canonicalize");
        }
        if (value == 0) {
            return "0";
        }
        BigDecimal shortest = shortestDigits(value);
        String digits = shortest.unscaledValue().abs().toString();
        int digitCount = digits.length();
        // The number is 0.d1d2...dk × 10^exponent, as ECMAScript's algorithm states it.
        int exponent = digitCount - shortest.scale();
        String sign = value < 0 ? "-" : "";
        if (digitCount <= exponent && exponent <= MAX_PLAIN_EXPONENT) {
            return sign + digits + "0".repeat(exponent - digitCount);
        }
        if (0 < exponent && exponent <= MAX_PLAIN_EXPONENT) {
            return sign + digits.substring(0, exponent) + "." + digits.substring(exponent);
        }
        if (MIN_PLAIN_EXPONENT < exponent && exponent <= 0) {
            return sign + "0." + "0".repeat(-exponent) + digits;
        }
        String mantissa = digitCount == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        int powerOfTen = exponent - 1;
        return sign + mantissa + "e" + (powerOfTen < 0 ? "-" : "+") + Math.abs(powerOfTen);
    }

    /**
     * The shortest decimal that reads back as {@code value}. Java's {@link Double#toString} gives it,
     * except that where one digit would do it may give two (4.9E-324 for 5e-324), so the one digit
     * closest to the exact value is tried first.
     */
    private static BigDecimal shortestDigits(double value) {
        BigDecimal javaDigits = new BigDecimal(Double.toString(value)).stripTrailingZeros();
        if (javaDigits.precision() == 2) {
            BigDecimal oneDigit = new BigDecimal(value).round(ONE_DIGIT);
            if (oneDigit.doubleValue() == value) {
                return oneDigit.stripTrailingZeros();
            }
        }
        return javaDigits;
    }

    private static String abbreviated(BigDecimal number) {
        String text = number.toString();
        return text.length() <= MAX_NUMBER_IN_MESSAGE ? text : text.substring(0, MAX_NUMBER_IN_MESSAGE) + "…";
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("every JDK has SHA-256", missing);
        }
    }
}
