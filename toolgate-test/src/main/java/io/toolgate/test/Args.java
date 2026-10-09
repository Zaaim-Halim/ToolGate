package io.toolgate.test;

import io.toolgate.api.ArgValue;
import io.toolgate.api.ObjectValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Turns the plain Java values tests write into argument values, as an adapter would from JSON. */
final class Args {

    private Args() {}

    static ObjectValue object(Map<String, ?> values) {
        var fields = new LinkedHashMap<String, ArgValue>();
        values.forEach((name, value) -> fields.put(name, value(value)));
        return ObjectValue.of(fields);
    }

    static ArgValue value(Object value) {
        return switch (value) {
            case null -> ArgValue.nullValue();
            case ArgValue argValue -> argValue;
            case String text -> ArgValue.text(text);
            case Boolean bool -> ArgValue.bool(bool);
            case BigDecimal number -> ArgValue.number(number);
            case BigInteger number -> ArgValue.number(new BigDecimal(number));
            case Integer number -> ArgValue.number(number);
            case Long number -> ArgValue.number(number);
            case Short number -> ArgValue.number(number);
            case Byte number -> ArgValue.number(number);
            // Through its decimal text, so 0.1 stays 0.1 rather than its binary approximation.
            case Double number -> ArgValue.number(new BigDecimal(Double.toString(number)));
            case Float number -> ArgValue.number(new BigDecimal(Float.toString(number)));
            case Map<?, ?> map -> {
                var fields = new LinkedHashMap<String, Object>();
                map.forEach((key, element) -> fields.put(String.valueOf(key), element));
                yield object(fields);
            }
            case Collection<?> list -> {
                var elements = new ArrayList<ArgValue>();
                list.forEach(element -> elements.add(value(element)));
                yield ArgValue.list(elements);
            }
            default -> throw new IllegalArgumentException("no argument value for a " + value.getClass().getName()
                    + "; use a String, number, Boolean, Map, Collection or ArgValue");
        };
    }
}
