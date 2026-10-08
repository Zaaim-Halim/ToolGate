package io.toolgate.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.toolgate.api.ArgValue;
import io.toolgate.api.ArgValueJson;

class ArgValueJsonFuzzTest {

    /**
     * Whatever text arrives: it either reads, and then writes and reads back to the same value, or
     * fails with IllegalArgumentException. Never another exception, never a different value.
     */
    @FuzzTest
    void readsStrictlyAndRoundTrips(FuzzedDataProvider data) {
        String text = data.consumeRemainingAsString();
        ArgValue value;
        try {
            value = ArgValueJson.read(text);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        String written = ArgValueJson.write(value);

        assertThat(ArgValueJson.read(written)).isEqualTo(value);
        assertThat(written.chars().noneMatch(c -> c < 0x20)).as(written).isTrue();
    }
}
