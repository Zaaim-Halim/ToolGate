package io.toolgate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArgValueTest {

    @Test
    void numbersWrittenDifferentlyAreEqual() {
        assertThat(ArgValue.number(new BigDecimal("1.00"))).isEqualTo(ArgValue.number(1));
        assertThat(ArgValue.number(new BigDecimal("1.00")).hashCode()).isEqualTo(ArgValue.number(1).hashCode());
    }

    @Test
    void objectKeepsFieldOrderButEqualityIgnoresIt() {
        var forward = new LinkedHashMap<String, ArgValue>();
        forward.put("to", ArgValue.text("a"));
        forward.put("body", ArgValue.text("b"));
        var backward = new LinkedHashMap<String, ArgValue>();
        backward.put("body", ArgValue.text("b"));
        backward.put("to", ArgValue.text("a"));

        assertThat(ObjectValue.of(forward).fields().keySet()).containsExactly("to", "body");
        assertThat(ObjectValue.of(forward)).isEqualTo(ObjectValue.of(backward));
    }

    @Test
    void javaNullIsRejectedEverywhere() {
        var withNull = new HashMap<String, ArgValue>();
        withNull.put("to", null);
        var listWithNull = new ArrayList<ArgValue>();
        listWithNull.add(null);

        assertThatNullPointerException().isThrownBy(() -> ObjectValue.of(withNull));
        assertThatNullPointerException().isThrownBy(() -> new ListValue(listWithNull));
        assertThatNullPointerException().isThrownBy(() -> new TextValue(null));
    }

    @Test
    void laterChangesToTheCallersCollectionsDoNotLeakIn() {
        var fields = new LinkedHashMap<String, ArgValue>();
        fields.put("to", ArgValue.text("a"));
        var elements = new ArrayList<ArgValue>(List.of(ArgValue.bool(true)));
        ObjectValue object = ObjectValue.of(fields);
        ListValue list = new ListValue(elements);

        fields.put("cc", ArgValue.text("b"));
        elements.add(ArgValue.nullValue());

        assertThat(object.fields()).containsOnlyKeys("to");
        assertThat(list.elements()).hasSize(1);
    }

    @Test
    void readsOneField() {
        ObjectValue args = ObjectValue.of(Map.of("id", ArgValue.text("INV-118")));

        assertThat(args.field("id")).contains(ArgValue.text("INV-118"));
        assertThat(args.field("missing")).isEmpty();
    }
}
