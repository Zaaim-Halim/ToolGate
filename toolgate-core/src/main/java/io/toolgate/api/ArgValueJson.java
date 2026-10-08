package io.toolgate.api;

import io.toolgate.audit.AuditJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes argument values as JSON, and reads them back strictly.
 *
 * <p>For stores that keep calls as text, such as a JDBC approval queue. The reader accepts exactly
 * RFC 8259 JSON and nothing a lenient parser would guess at: duplicate keys (which parsers resolve
 * differently, and {@link ObjectValue} cannot hold), trailing content, numbers outside the JSON
 * grammar ({@code 01}, {@code +1}, {@code .5}, {@code NaN}), comments, and nesting deeper than
 * {@value #MAX_DEPTH} levels.
 */
public final class ArgValueJson {

    /** Deepest nesting the reader accepts, so hostile input cannot exhaust the stack. */
    public static final int MAX_DEPTH = 256;

    private ArgValueJson() {}

    /**
     * Writes a value as compact JSON. Text is escaped strictly, so the output is one line.
     *
     * @param value the value
     * @return its JSON
     */
    public static String write(ArgValue value) {
        var json = new StringBuilder();
        write(Objects.requireNonNull(value, "value"), json);
        return json.toString();
    }

    /**
     * Reads JSON written by {@link #write}, or any strictly valid JSON.
     *
     * @param json the text
     * @return the value
     * @throws IllegalArgumentException if the text is not strictly valid JSON, has duplicate keys,
     *                                  or nests deeper than {@value #MAX_DEPTH} levels
     */
    public static ArgValue read(String json) {
        var reader = new Reader(Objects.requireNonNull(json, "json"));
        reader.skipWhitespace();
        ArgValue value = reader.value(0);
        reader.skipWhitespace();
        if (!reader.atEnd()) {
            throw reader.error("trailing content after the value");
        }
        return value;
    }

    private static void write(ArgValue value, StringBuilder json) {
        switch (value) {
            case TextValue(String text) -> json.append(AuditJson.quote(text));
            case NumberValue(BigDecimal number) -> json.append(number.toString());
            case BooleanValue(boolean bool) -> json.append(bool);
            case NullValue nothing -> json.append("null");
            case ListValue(List<ArgValue> elements) -> {
                json.append('[');
                for (int index = 0; index < elements.size(); index++) {
                    if (index > 0) {
                        json.append(',');
                    }
                    write(elements.get(index), json);
                }
                json.append(']');
            }
            case ObjectValue(Map<String, ArgValue> fields) -> {
                json.append('{');
                boolean first = true;
                for (Map.Entry<String, ArgValue> field : fields.entrySet()) {
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    json.append(AuditJson.quote(field.getKey())).append(':');
                    write(field.getValue(), json);
                }
                json.append('}');
            }
        }
    }

    /** A recursive-descent reader over the RFC 8259 grammar. */
    private static final class Reader {

        private final String text;
        private int position;

        Reader(String text) {
            this.text = text;
        }

        ArgValue value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting deeper than " + MAX_DEPTH + " levels");
            }
            if (atEnd()) {
                throw error("expected a value");
            }
            char c = text.charAt(position);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> list(depth);
                case '"' -> new TextValue(string());
                case 't' -> literal("true", BooleanValue.class);
                case 'f' -> literal("false", BooleanValue.class);
                case 'n' -> literal("null", NullValue.class);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        private ArgValue object(int depth) {
            position++;
            var fields = new LinkedHashMap<String, ArgValue>();
            skipWhitespace();
            if (consume('}')) {
                return new ObjectValue(fields);
            }
            do {
                skipWhitespace();
                if (atEnd() || text.charAt(position) != '"') {
                    throw error("expected a field name");
                }
                int keyAt = position;
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                ArgValue fieldValue = value(depth + 1);
                if (fields.putIfAbsent(key, fieldValue) != null) {
                    position = keyAt;
                    throw error("duplicate key \"" + key + "\"");
                }
                skipWhitespace();
            } while (consume(','));
            expect('}');
            return new ObjectValue(fields);
        }

        private ArgValue list(int depth) {
            position++;
            var elements = new ArrayList<ArgValue>();
            skipWhitespace();
            if (consume(']')) {
                return new ListValue(elements);
            }
            do {
                skipWhitespace();
                elements.add(value(depth + 1));
                skipWhitespace();
            } while (consume(','));
            expect(']');
            return new ListValue(elements);
        }

        private String string() {
            position++;
            var out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error("unescaped control character in a string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("unterminated escape");
                }
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(unicodeEscape());
                    default -> throw error("invalid escape \\" + escape);
                }
            }
        }

        private char unicodeEscape() {
            if (position + 4 > text.length()) {
                throw error("short \\u escape");
            }
            int value = 0;
            for (int index = 0; index < 4; index++) {
                char c = text.charAt(position++);
                int digit = Character.digit(c, 16);
                if (digit < 0 || c > 'f') {
                    throw error("invalid \\u escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        /** The JSON number grammar exactly: -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)? */
        private ArgValue number() {
            int start = position;
            consume('-');
            if (consume('0')) {
                if (!atEnd() && isDigit(text.charAt(position))) {
                    throw error("leading zero in a number");
                }
            } else if (!digits()) {
                throw error("expected digits");
            }
            if (consume('.') && !digits()) {
                throw error("expected digits after the decimal point");
            }
            if (!atEnd() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                position++;
                if (!consume('+')) {
                    consume('-');
                }
                if (!digits()) {
                    throw error("expected exponent digits");
                }
            }
            try {
                return new NumberValue(new BigDecimal(text.substring(start, position)));
            } catch (NumberFormatException | ArithmeticException outOfRange) {
                throw error("number out of range");
            }
        }

        private boolean digits() {
            int start = position;
            while (!atEnd() && isDigit(text.charAt(position))) {
                position++;
            }
            return position > start;
        }

        private ArgValue literal(String word, Class<? extends ArgValue> type) {
            if (!text.startsWith(word, position)) {
                throw error("unexpected text");
            }
            position += word.length();
            return type == NullValue.class ? NullValue.INSTANCE : new BooleanValue(word.equals("true"));
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = text.charAt(position);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                position++;
            }
        }

        private boolean consume(char expected) {
            if (!atEnd() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        boolean atEnd() {
            return position >= text.length();
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        IllegalArgumentException error(String problem) {
            return new IllegalArgumentException("invalid JSON at position " + position + ": " + problem);
        }
    }
}
