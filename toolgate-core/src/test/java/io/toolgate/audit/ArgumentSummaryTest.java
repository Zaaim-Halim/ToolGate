package io.toolgate.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.api.ArgValue;
import io.toolgate.api.ObjectValue;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class ArgumentSummaryTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes();
    private static final byte[] OTHER_KEY = "fedcba9876543210fedcba9876543210".getBytes();

    @Test
    void withoutAKeyLogsTypesAndSizesOnly() {
        var args = new LinkedHashMap<String, ArgValue>();
        args.put("to", ArgValue.text("ops@mycompany.com"));
        args.put("amount", ArgValue.number(480));
        args.put("urgent", ArgValue.bool(true));
        args.put("cc", ArgValue.nullValue());
        args.put("attachments", ArgValue.list(List.of(ArgValue.text("a"), ArgValue.text("b"))));
        args.put("meta", ArgValue.object(Map.of("k", ArgValue.text("v"))));

        assertThat(ArgumentSummary.typesOnly().summarize(ObjectValue.of(args))).containsExactly(
                Map.entry("amount", "number"), Map.entry("attachments", "list(2)"), Map.entry("cc", "null"),
                Map.entry("meta", "object(1)"), Map.entry("to", "text(17)"), Map.entry("urgent", "boolean"));
    }

    @Test
    void textSizeCountsCharactersNotUtf16Units() {
        assertThat(ArgumentSummary.typesOnly().summarize(args("😀é"))).containsEntry("v", "text(2)");
    }

    @Test
    void keyedHashesHideTheValue() {
        String summary = ArgumentSummary.keyed(KEY).summarize(args("ops@mycompany.com")).get("v");

        assertThat(summary).matches("hmac:[0-9a-f]{32}").doesNotContain("mycompany");
    }

    @Test
    void equalValuesCorrelateAcrossInstancesWithTheSameKey() {
        assertThat(ArgumentSummary.keyed(KEY).summarize(args("a@x.example")))
                .isEqualTo(ArgumentSummary.keyed(KEY.clone()).summarize(args("a@x.example")));
        assertThat(ArgumentSummary.keyed(KEY).summarize(args("a@x.example")))
                .isNotEqualTo(ArgumentSummary.keyed(OTHER_KEY).summarize(args("a@x.example")));
    }

    @Test
    void numbersWrittenDifferentlyHashTheSame() {
        var summary = ArgumentSummary.keyed(KEY);

        assertThat(summary.summarize(ObjectValue.of(Map.of("v", ArgValue.number(new BigDecimal("480.00"))))))
                .isEqualTo(summary.summarize(ObjectValue.of(Map.of("v", ArgValue.number(480)))));
    }

    @Test
    void objectFieldOrderDoesNotMatter() {
        var forward = new LinkedHashMap<String, ArgValue>();
        forward.put("a", ArgValue.text("1"));
        forward.put("b", ArgValue.text("2"));
        var backward = new LinkedHashMap<String, ArgValue>();
        backward.put("b", ArgValue.text("2"));
        backward.put("a", ArgValue.text("1"));

        assertThat(ArgumentSummary.canonical(ObjectValue.of(forward)))
                .isEqualTo(ArgumentSummary.canonical(ObjectValue.of(backward)));
    }

    @Test
    void typesNeverShareAnEncoding() {
        assertThat(ArgumentSummary.canonical(ArgValue.text("1")))
                .isNotEqualTo(ArgumentSummary.canonical(ArgValue.number(1)));
        assertThat(ArgumentSummary.canonical(ArgValue.text("true")))
                .isNotEqualTo(ArgumentSummary.canonical(ArgValue.bool(true)));
        assertThat(ArgumentSummary.canonical(ArgValue.list(List.of(ArgValue.text("a"), ArgValue.text("b")))))
                .isNotEqualTo(ArgumentSummary.canonical(ArgValue.list(List.of(ArgValue.text("ab")))));
    }

    @Property
    void differentTextNeverSharesAnEncoding(@ForAll String first, @ForAll String second) {
        Assume.that(!first.equals(second));

        assertThat(ArgumentSummary.canonical(ArgValue.text(first)))
                .isNotEqualTo(ArgumentSummary.canonical(ArgValue.text(second)));
    }

    @Test
    void keyIsCopiedAndMustBeLongEnough() {
        byte[] key = KEY.clone();
        var summary = ArgumentSummary.keyed(key);
        String before = summary.summarize(args("x")).get("v");
        Arrays.fill(key, (byte) 0);

        assertThat(summary.summarize(args("x")).get("v")).isEqualTo(before);
        assertThatIllegalArgumentException().isThrownBy(() -> ArgumentSummary.keyed(new byte[31]));
    }

    private static ObjectValue args(String value) {
        return ObjectValue.of(Map.of("v", ArgValue.text(value)));
    }
}
