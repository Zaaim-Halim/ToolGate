package io.toolgate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArgValueJsonTest {

    /** Found by running a released refund: 480 came back as 4.8E+2 for a tool reading text. */
    @Test
    void writesNumbersAsAModelWouldWriteThem() {
        assertThat(ArgValueJson.write(ArgValueJson.read("[480,1000.0,0.50,1e-7,-12]"))).isEqualTo("[480,1000,0.5,0.0000001,-12]");
        assertThat(ArgValueJson.write(ArgValueJson.read("1e999999999"))).isEqualTo("1E+999999999");
    }

    @Test
    void writesCompactJson() {
        var fields = new LinkedHashMap<String, ArgValue>();
        fields.put("order", ArgValue.number(7731));
        fields.put("amount", ArgValue.number(new BigDecimal("480.50")));
        fields.put("note", ArgValue.text("line one\n\"quoted\""));
        fields.put("urgent", ArgValue.bool(true));
        fields.put("cc", ArgValue.nullValue());
        fields.put("tags", ArgValue.list(List.of(ArgValue.text("a"), ArgValue.text("b"))));

        assertThat(ArgValueJson.write(ArgValue.object(fields))).isEqualTo(
                "{\"order\":7731,\"amount\":480.5,\"note\":\"line one\\n\\\"quoted\\\"\",\"urgent\":true,\"cc\":null,"
                        + "\"tags\":[\"a\",\"b\"]}");
    }

    @Test
    void readsStandardJsonWithWhitespaceAndEscapes() {
        ArgValue value = ArgValueJson.read(" {\r\n\t\"a\" : [ 1 , -0.5e2 , \"\\u00e9\\ud83d\\ude00\\/\" ] , \"b\":{} } ");

        assertThat(value).isEqualTo(ArgValue.object(Map.of(
                "a", ArgValue.list(List.of(ArgValue.number(1), ArgValue.number(new BigDecimal("-50")),
                        ArgValue.text("\u00e9\ud83d\ude00/"))),
                "b", ArgValue.object(Map.of()))));
    }

    @Property(tries = 500)
    void everyValueReadsBackUnchanged(@ForAll("values") ArgValue value) {
        assertThat(ArgValueJson.read(ArgValueJson.write(value))).isEqualTo(value);
    }

    @Property(tries = 2000)
    void arbitraryTextEitherReadsOrFailsWithIllegalArgument(@ForAll String text) {
        try {
            ArgValue value = ArgValueJson.read(text);
            assertThat(ArgValueJson.read(ArgValueJson.write(value))).isEqualTo(value);
        } catch (IllegalArgumentException invalid) {
            assertThat(invalid).hasMessageStartingWith("invalid JSON");
        }
    }

    /** Each of these is read differently by some lenient parser, so none is read at all. */
    @ParameterizedTest
    @ValueSource(strings = {
        "", " ", "{\"a\":1,\"a\":2}", "{\"a\":1} x", "[1,]", "{\"a\":1,}", "[01]", "[+1]", "[.5]", "[1.]", "[1e]",
        "[NaN]", "[Infinity]", "[0x1F]", "{'a':1}", "{a:1}", "[\"\\x41\"]", "[\"\\u12\"]", "[\"\\uGGGG\"]",
        "[\"a\nb\"]", "// comment\n1", "[1]/*x*/", "tru", "nul", "\"unterminated", "[1 2]", "{\"a\" 1}", "\ufeff1",
    })
    void rejectsAnythingNotStrictlyJson(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> ArgValueJson.read(text));
    }

    @Test
    void rejectsDeepNestingButAcceptsTheLimit() {
        String deepest = "[".repeat(ArgValueJson.MAX_DEPTH + 1) + "]".repeat(ArgValueJson.MAX_DEPTH + 1);
        String tooDeep = "[".repeat(ArgValueJson.MAX_DEPTH + 2) + "]".repeat(ArgValueJson.MAX_DEPTH + 2);

        assertThat(ArgValueJson.read(deepest)).isInstanceOf(ListValue.class);
        assertThatIllegalArgumentException().isThrownBy(() -> ArgValueJson.read(tooDeep))
                .withMessageContaining("nesting");
    }

    @Test
    void readsHugeExponentsWithoutLosingThem() {
        assertThat(ArgValueJson.read("1e999")).isEqualTo(ArgValue.number(new BigDecimal("1e999")));
        assertThatIllegalArgumentException().isThrownBy(() -> ArgValueJson.read("1e99999999999"));
    }

    /** Depth is bounded: an unbounded lazyOf tree occasionally grew until the test JVM ran out of heap. */
    @Provide
    Arbitrary<ArgValue> values() {
        return Arbitraries.recursive(ArgValueJsonTest::scalars, nested -> Arbitraries.oneOf(
                nested.list().ofMaxSize(4).map(ArgValue::list),
                Arbitraries.maps(Arbitraries.strings().ofMaxLength(8), nested).ofMaxSize(4).map(ArgValue::object)), 0, 4);
    }

    private static Arbitrary<ArgValue> scalars() {
        return Arbitraries.oneOf(
                Arbitraries.strings().map(ArgValue::text),
                Arbitraries.bigDecimals().map(ArgValue::number),
                Arbitraries.of(true, false).map(ArgValue::bool),
                Arbitraries.just(ArgValue.nullValue()));
    }
}
