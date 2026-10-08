package io.toolgate.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.toolgate.validate.IpAddress;
import io.toolgate.validate.IpLiterals;

class IpLiteralsFuzzTest {

    /** Never throws, and every address it accepts reads back from its canonical form. */
    @FuzzTest
    void parsesWithoutThrowingAndRoundTrips(FuzzedDataProvider data) {
        String text = data.consumeRemainingAsString();

        if (IpLiterals.parseIpv4Host(text) instanceof IpLiterals.Address(IpAddress address)) {
            assertThat(IpLiterals.parseIpv4Host(address.toString())).isEqualTo(new IpLiterals.Address(address));
        }
        IpLiterals.Parsed ipv6 = IpLiterals.parseIpv6(text);
        assertThat(ipv6).isNotInstanceOf(IpLiterals.NotAnAddress.class);
        if (ipv6 instanceof IpLiterals.Address(IpAddress address)) {
            assertThat(IpLiterals.parseIpv6(address.toString())).isEqualTo(new IpLiterals.Address(address));
        }
    }
}
