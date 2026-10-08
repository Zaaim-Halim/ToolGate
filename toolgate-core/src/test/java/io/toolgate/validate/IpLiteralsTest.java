package io.toolgate.validate;

import static org.assertj.core.api.Assertions.assertThat;

import io.toolgate.validate.IpLiterals.Address;
import io.toolgate.validate.IpLiterals.Malformed;
import io.toolgate.validate.IpLiterals.NotAnAddress;
import io.toolgate.validate.IpLiterals.Parsed;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class IpLiteralsTest {

    @Test
    void parsesCanonicalIpv4() {
        assertThat(IpLiterals.parseIpv4Host("169.254.169.254"))
                .isEqualTo(new Address(new IpAddress.V4(0xa9fea9fe)));
        assertThat(IpLiterals.parseIpv4Host("0.0.0.0")).isEqualTo(new Address(new IpAddress.V4(0)));
        assertThat(IpLiterals.parseIpv4Host("255.255.255.255")).isEqualTo(new Address(new IpAddress.V4(-1)));
    }

    /** Every one of these reaches 169.254.169.254, or 127.0.0.1, through some resolver or client. */
    @ParameterizedTest
    @ValueSource(strings = {
        "2852039166", "169.254.43518", "169.16689662", "0xa9fea9fe", "0xa9.0xfe.0xa9.0xfe",
        "0251.0376.0251.0376", "0169.254.169.254", "127.1", "127.0.1", "0x7f.1", "1.2.3.4.5",
        "256.1.1.1", "1.2.3.", "example.123", "example.0x", "a.0x1f", "1..2.3", "01.2.3.4",
    })
    void rejectsNonCanonicalIpv4(String host) {
        assertThat(IpLiterals.parseIpv4Host(host)).isInstanceOf(Malformed.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "deadbeef", "a1.b2", "123.example", "0x7f.example", "localhost"})
    void namesAreNotAddresses(String host) {
        assertThat(IpLiterals.parseIpv4Host(host)).isInstanceOf(NotAnAddress.class);
    }

    @Test
    void rejectsDigitsFromOtherScripts() {
        // U+0661 is ARABIC-INDIC DIGIT ONE, which Character.isDigit accepts.
        assertThat(IpLiterals.parseIpv4Host("١.2.3.4")).isNotInstanceOf(Address.class);
        assertThat(IpLiterals.parseIpv6("١::1")).isInstanceOf(Malformed.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "::", "::1", "1::", "fd00:ec2::254", "FD00:EC2::254", "::ffff:169.254.169.254", "::169.254.169.254",
        "64:ff9b::a9fe:a9fe", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6::8", "1::3:4:5:6:7:8", "1:2:3:4:5:6:1.2.3.4",
        "0000:0000:0000:0000:0000:0000:0000:0001", "2001:db8::1:0:0:1",
    })
    void parsesIpv6LikeTheJdk(String text) throws UnknownHostException {
        assertThat(IpLiterals.parseIpv6(text)).isEqualTo(new Address(fromJdk(text)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", ":", ":::", "1:::2", "1::2::3", ":1::", "1::2:", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9",
        "1:2:3:4:5:6:7:8::", "::1:2:3:4:5:6:7:8", "12345::", "g::", "1.2.3.4::", "::1.2.3",
        "::ffff:0251.0376.0251.0376", "::ffff:2852039166", "fe80::1%25eth0", "fe80::1%eth0", " ::1", "::1 ",
    })
    void rejectsMalformedIpv6(String text) {
        assertThat(IpLiterals.parseIpv6(text)).isInstanceOf(Malformed.class);
    }

    @Property
    void everyIpv4AddressRoundTrips(@ForAll int bits) {
        var address = new IpAddress.V4(bits);

        assertThat(IpLiterals.parseIpv4Host(address.toString())).isEqualTo(new Address(address));
    }

    @Property
    void everyIpv6AddressRoundTrips(@ForAll long high, @ForAll long low) throws UnknownHostException {
        var address = new IpAddress.V6(high, low);
        byte[] bytes = ByteBuffer.allocate(16).putLong(high).putLong(low).array();
        // The JDK prints groups without leading zeros; ToolGate prints them full. Both must parse.
        String jdkText = InetAddress.getByAddress(bytes).getHostAddress();

        assertThat(IpLiterals.parseIpv6(address.toString())).isEqualTo(new Address(address));
        assertThat(IpLiterals.parseIpv6(jdkText)).isEqualTo(new Address(address));
    }

    @Property
    void neverThrowsOnArbitraryText(@ForAll("hostish") String text) {
        Parsed asIpv4 = IpLiterals.parseIpv4Host(text);
        Parsed asIpv6 = IpLiterals.parseIpv6(text);

        assertThat(asIpv4).isNotNull();
        assertThat(asIpv6).isNotInstanceOf(NotAnAddress.class);
    }

    @Provide
    Arbitrary<String> hostish() {
        return Arbitraries.strings().withChars("0123456789abcdefx.:%ABCDEF١ ").ofMaxLength(45);
    }

    /** IPv6 literals are parsed by the JDK itself, never resolved. */
    private static IpAddress fromJdk(String text) throws UnknownHostException {
        byte[] bytes = InetAddress.getByName(text).getAddress();
        if (bytes.length == 4) {
            // The JDK unwraps ::ffff:a.b.c.d to IPv4; ToolGate keeps the IPv6 form so the range table sees it.
            return new IpAddress.V6(0, 0xffffL << 32 | (ByteBuffer.wrap(bytes).getInt() & 0xffffffffL));
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new IpAddress.V6(buffer.getLong(), buffer.getLong());
    }
}
