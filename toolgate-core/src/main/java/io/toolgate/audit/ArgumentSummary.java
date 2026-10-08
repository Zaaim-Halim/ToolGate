package io.toolgate.audit;

import io.toolgate.api.ArgValue;
import io.toolgate.api.BooleanValue;
import io.toolgate.api.ListValue;
import io.toolgate.api.NullValue;
import io.toolgate.api.NumberValue;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.TextValue;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Summarizes argument values for audit logs, which often end up less protected than the data.
 *
 * <p>With a key, each value becomes a keyed hash (HMAC-SHA256): equal values give equal hashes, so
 * an investigator can correlate calls, but a value cannot be recovered by hashing guesses, as it
 * could from a plain SHA-256 of an email address. Without a key, only each value's type and size
 * are logged.
 */
public final class ArgumentSummary {

    /** HMAC-SHA256's block-size recommendation; shorter keys weaken it. */
    public static final int MIN_KEY_BYTES = 32;

    /** 128 bits of the MAC: plenty to correlate values, and shorter log lines. */
    private static final int HASH_BYTES = 16;
    private static final String ALGORITHM = "HmacSHA256";

    private final Function<ArgValue, String> summarizer;

    private ArgumentSummary(Function<ArgValue, String> summarizer) {
        this.summarizer = summarizer;
    }

    /**
     * Logs each argument's type and size only.
     *
     * @return the summary
     */
    public static ArgumentSummary typesOnly() {
        return new ArgumentSummary(ArgumentSummary::type);
    }

    /**
     * Logs each argument as a keyed hash.
     *
     * @param key at least {@value #MIN_KEY_BYTES} secret bytes, the same on every instance that
     *            should produce comparable hashes
     * @return the summary
     * @throws IllegalArgumentException if the key is too short
     */
    public static ArgumentSummary keyed(byte[] key) {
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("argument hash key needs at least " + MIN_KEY_BYTES + " bytes");
        }
        var secret = new SecretKeySpec(Arrays.copyOf(key, key.length), ALGORITHM);
        return new ArgumentSummary(value -> "hmac:" + HexFormat.of().formatHex(mac(secret, value), 0, HASH_BYTES));
    }

    /**
     * Summarizes every argument of one call.
     *
     * @param args the arguments
     * @return each argument's summary by name, sorted by name
     */
    public Map<String, String> summarize(ObjectValue args) {
        var summaries = new LinkedHashMap<String, String>();
        args.fields().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(field -> summaries.put(field.getKey(), summarizer.apply(field.getValue())));
        return summaries;
    }

    private static String type(ArgValue value) {
        return switch (value) {
            case TextValue(String text) -> "text(" + text.codePointCount(0, text.length()) + ")";
            case NumberValue number -> "number";
            case BooleanValue bool -> "boolean";
            case NullValue nothing -> "null";
            case ListValue(var elements) -> "list(" + elements.size() + ")";
            case ObjectValue(var fields) -> "object(" + fields.size() + ")";
        };
    }

    private static byte[] mac(SecretKeySpec secret, ArgValue value) {
        try {
            // Mac instances are not thread-safe, and creating one costs microseconds; this runs off the request path.
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secret);
            return mac.doFinal(canonical(value));
        } catch (GeneralSecurityException impossible) {
            // Every Java platform must provide HmacSHA256.
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * An unambiguous encoding: a type tag, then length-prefixed content, with object fields sorted,
     * so equal values hash equally and different values never share an encoding.
     */
    static byte[] canonical(ArgValue value) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            write(out, value);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    private static void write(DataOutputStream out, ArgValue value) throws IOException {
        switch (value) {
            case TextValue(String text) -> {
                out.writeByte('T');
                writeString(out, text);
            }
            case NumberValue(var number) -> {
                out.writeByte('N');
                writeString(out, number.toString());
            }
            case BooleanValue(boolean bool) -> {
                out.writeByte('B');
                out.writeBoolean(bool);
            }
            case NullValue nothing -> out.writeByte('Z');
            case ListValue(var elements) -> {
                out.writeByte('L');
                out.writeInt(elements.size());
                for (ArgValue element : elements) {
                    write(out, element);
                }
            }
            case ObjectValue(var fields) -> {
                out.writeByte('O');
                out.writeInt(fields.size());
                for (var field : fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    writeString(out, field.getKey());
                    write(out, field.getValue());
                }
            }
        }
    }

    private static void writeString(DataOutputStream out, String text) throws IOException {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }
}
