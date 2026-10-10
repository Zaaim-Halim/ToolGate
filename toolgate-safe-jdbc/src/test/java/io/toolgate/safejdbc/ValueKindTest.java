package io.toolgate.safejdbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.safejdbc.SafeJdbc.ValueKind;
import java.sql.Types;
import org.junit.jupiter.api.Test;

class ValueKindTest {

    @Test
    void onlyASingleBitIsABoolean() {
        assertThat(ValueKind.of(Types.BIT, 1)).contains(ValueKind.BOOLEAN);
        assertThat(ValueKind.of(Types.BIT, 0)).contains(ValueKind.BOOLEAN);
        assertThat(ValueKind.of(Types.BIT, 8)).isEmpty();
        assertThat(ValueKind.of(Types.BOOLEAN, 0)).contains(ValueKind.BOOLEAN);
    }

    @Test
    void binaryLargeAndDriverSpecificTypesAreRefused() {
        assertThat(ValueKind.of(Types.BLOB, 0)).isEmpty();
        assertThat(ValueKind.of(Types.CLOB, 0)).isEmpty();
        assertThat(ValueKind.of(Types.VARBINARY, 16)).isEmpty();
        assertThat(ValueKind.of(Types.OTHER, 0)).isEmpty();
        assertThat(ValueKind.of(Types.ARRAY, 0)).isEmpty();
    }
}
