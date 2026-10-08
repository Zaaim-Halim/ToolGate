package io.toolgate.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.api.ArgValue;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class OutputPathTest {

    private static final ArgValue SEARCH = ArgValue.object(Map.of("items", ArgValue.list(List.of(
            ArgValue.object(Map.of("id", ArgValue.text("INV-118"), "tenantId", ArgValue.text("acme"))),
            ArgValue.object(Map.of("id", ArgValue.text("INV-900"), "tenantId", ArgValue.text("globex")))))));

    @Test
    void selectsEveryElementsField() {
        assertThat(OutputPath.parse("items[*].tenantId").select(SEARCH))
                .contains(List.of(ArgValue.text("acme"), ArgValue.text("globex")));
    }

    @Test
    void selectsANestedField() {
        ArgValue output = ArgValue.object(Map.of("customer", ArgValue.object(Map.of("id", ArgValue.text("C-1")))));

        assertThat(OutputPath.parse("customer.id").select(output)).contains(List.of(ArgValue.text("C-1")));
    }

    @Test
    void selectsFromATopLevelArrayAndNestedArrays() {
        ArgValue rows = ArgValue.list(List.of(ArgValue.object(Map.of("owner", ArgValue.text("maria")))));
        ArgValue grid = ArgValue.object(Map.of("pages", ArgValue.list(List.of(
                ArgValue.list(List.of(ArgValue.object(Map.of("t", ArgValue.text("acme")))))))));

        assertThat(OutputPath.parse("[*].owner").select(rows)).contains(List.of(ArgValue.text("maria")));
        assertThat(OutputPath.parse("pages[*][*].t").select(grid)).contains(List.of(ArgValue.text("acme")));
    }

    /** An empty search is a normal result; a missing field means the output changed shape. */
    @Test
    void emptyArrayIsNoValuesButMissingFieldIsAShapeMismatch() {
        ArgValue empty = ArgValue.object(Map.of("items", ArgValue.list(List.of())));
        ArgValue renamed = ArgValue.object(Map.of("results", ArgValue.list(List.of())));
        ArgValue oneWithoutTenant = ArgValue.object(Map.of("items", ArgValue.list(List.of(
                ArgValue.object(Map.of("tenantId", ArgValue.text("acme"))),
                ArgValue.object(Map.of("id", ArgValue.text("INV-7")))))));

        OutputPath path = OutputPath.parse("items[*].tenantId");
        assertThat(path.select(empty)).contains(List.of());
        assertThat(path.select(renamed)).isEmpty();
        assertThat(path.select(oneWithoutTenant)).isEmpty();
        assertThat(path.select(ArgValue.text("plain text"))).isEmpty();
        assertThat(path.select(ArgValue.object(Map.of("items", ArgValue.text("x"))))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "a.", ".a", "a..b", "a[*]b", "a[0]", "a[?(@.x)]", "$.a", "a.*", "1a", "a b", "a[*", "*"})
    void rejectsAnythingOutsideTheLanguage(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> OutputPath.parse(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "customer.id", "items[*].tenantId", "[*]", "[*].a", "a[*][*].b-c", "_x.y_2"})
    void acceptsTheLanguage(String text) {
        assertThat(OutputPath.parse(text)).hasToString(text);
    }

    @Property
    void parsingNeverThrowsAnythingButIllegalArgument(@ForAll String text) {
        try {
            OutputPath.parse(text).select(SEARCH);
        } catch (IllegalArgumentException invalid) {
            // expected for text outside the language
        }
    }

    @Test
    void selectNeverInventsValues() {
        assertThat(OutputPath.parse("items[*].missing").select(SEARCH)).isEqualTo(Optional.empty());
    }
}
