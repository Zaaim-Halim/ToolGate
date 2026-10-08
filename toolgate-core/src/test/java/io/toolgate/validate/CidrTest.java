package io.toolgate.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class CidrTest {

    @Test
    void containsItsOwnEdgesOnly() {
        Cidr block = Cidr.parse("172.16.0.0/12");

        assertThat(block.contains(ipv4("172.16.0.0"))).isTrue();
        assertThat(block.contains(ipv4("172.31.255.255"))).isTrue();
        assertThat(block.contains(ipv4("172.15.255.255"))).isFalse();
        assertThat(block.contains(ipv4("172.32.0.0"))).isFalse();
    }

    @Test
    void ipv6PrefixesOnBothSidesOfTheMiddle() {
        assertThat(Cidr.parse("fc00::/7").contains(ipv6("fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"))).isTrue();
        assertThat(Cidr.parse("fc00::/7").contains(ipv6("fe00::"))).isFalse();
        assertThat(Cidr.parse("::ffff:0:0/96").contains(ipv6("::ffff:a9fe:a9fe"))).isTrue();
        assertThat(Cidr.parse("::ffff:0:0/96").contains(ipv6("::1:0:0"))).isFalse();
        assertThat(Cidr.parse("::1/128").contains(ipv6("::1"))).isTrue();
        assertThat(Cidr.parse("::1/128").contains(ipv6("::2"))).isFalse();
    }

    @Test
    void familiesNeverMatchEachOther() {
        assertThat(Cidr.parse("0.0.0.0/0").contains(ipv6("::"))).isFalse();
        assertThat(Cidr.parse("::/0").contains(ipv4("0.0.0.0"))).isFalse();
    }

    @Test
    void rejectsMalformedBlocks() {
        assertThatIllegalArgumentException().isThrownBy(() -> Cidr.parse("10.0.0.1/8"));
        assertThatIllegalArgumentException().isThrownBy(() -> Cidr.parse("10.0.0.0/33"));
        assertThatIllegalArgumentException().isThrownBy(() -> Cidr.parse("::/129"));
        assertThatIllegalArgumentException().isThrownBy(() -> Cidr.parse("10.0.0.0"));
        assertThatIllegalArgumentException().isThrownBy(() -> Cidr.parse("example.com/8"));
    }

    @Property
    void ipv4BlockContainsExactlyItsRange(@ForAll int network, @ForAll @IntRange(max = 32) int prefix,
                                          @ForAll int candidate) {
        int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
        var block = new Cidr(new IpAddress.V4(network & mask), prefix);

        assertThat(block.contains(new IpAddress.V4(candidate))).isEqualTo((candidate & mask) == (network & mask));
    }

    @Property
    void ipv6BlockContainsItsFirstAndLastAddress(@ForAll long high, @ForAll long low,
                                                 @ForAll @IntRange(max = 128) int prefix) {
        BigInteger all = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
        BigInteger value = toBig(high, low);
        BigInteger hostBits = BigInteger.ONE.shiftLeft(128 - prefix).subtract(BigInteger.ONE);
        BigInteger first = value.and(all.xor(hostBits));
        BigInteger last = first.or(hostBits);
        var block = new Cidr(fromBig(first), prefix);

        assertThat(block.contains(fromBig(first))).isTrue();
        assertThat(block.contains(fromBig(last))).isTrue();
        if (prefix > 0) {
            assertThat(block.contains(fromBig(last.add(BigInteger.ONE).and(all)))).isFalse();
        }
    }

    private static BigInteger toBig(long high, long low) {
        return new BigInteger(1, ByteBuffer.allocate(16).putLong(high).putLong(low).array());
    }

    private static IpAddress.V6 fromBig(BigInteger value) {
        return new IpAddress.V6(value.shiftRight(64).longValue(), value.longValue());
    }

    static IpAddress ipv4(String text) {
        return ((IpLiterals.Address) IpLiterals.parseIpv4Host(text)).address();
    }

    static IpAddress ipv6(String text) {
        return ((IpLiterals.Address) IpLiterals.parseIpv6(text)).address();
    }
}
