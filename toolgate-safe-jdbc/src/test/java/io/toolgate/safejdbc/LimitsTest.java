package io.toolgate.safejdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.api.ArgValue;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LimitsTest {

    @Test
    void defaultsAndChanges() {
        assertThat(Limits.defaults()).isEqualTo(new Limits(100, Duration.ofSeconds(5), 1_000));
        assertThat(Limits.defaults().maxRows(20).timeout(Duration.ofSeconds(2)).maxCellLength(50))
                .isEqualTo(new Limits(20, Duration.ofSeconds(2), 50));
    }

    @Test
    void roundsTheTimeoutUpToWholeSeconds() {
        assertThat(Limits.defaults().timeout(Duration.ofMillis(1)).timeoutSeconds()).isEqualTo(1);
        assertThat(Limits.defaults().timeout(Duration.ofMillis(2_001)).timeoutSeconds()).isEqualTo(3);
        assertThat(Limits.defaults().timeout(Duration.ofSeconds(2)).timeoutSeconds()).isEqualTo(2);
    }

    @Test
    void boundsArePositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> Limits.defaults().maxRows(0));
        assertThatIllegalArgumentException().isThrownBy(() -> Limits.defaults().timeout(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> Limits.defaults().timeout(Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> Limits.defaults().maxCellLength(0));
    }

    @Test
    void rowsHaveOneValuePerColumn() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new Rows(List.of("a", "b"), List.of(List.of(ArgValue.number(1))), false));
    }
}
