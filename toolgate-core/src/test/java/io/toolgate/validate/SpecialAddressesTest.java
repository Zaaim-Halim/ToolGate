package io.toolgate.validate;

import static io.toolgate.validate.CidrTest.ipv4;
import static io.toolgate.validate.CidrTest.ipv6;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SpecialAddressesTest {

    @ParameterizedTest
    @CsvSource({
        "0.0.0.0,         this network",
        "10.255.255.255,  private network",
        "100.100.100.200, shared address space",
        "127.0.0.1,       loopback",
        "169.254.169.254, 'link-local, including cloud metadata'",
        "172.16.0.1,      private network",
        "192.0.0.192,     IETF protocol assignments",
        "192.168.1.1,     private network",
        "198.19.255.255,  benchmarking",
        "224.0.0.1,       multicast",
        "255.255.255.255, 'reserved, including broadcast'",
    })
    void deniesSpecialIpv4(String address, String name) {
        assertThat(SpecialAddresses.find(ipv4(address))).hasValueSatisfying(range -> assertThat(range.name()).isEqualTo(name));
    }

    @ParameterizedTest
    @CsvSource({
        "::,                     unspecified",
        "::1,                    loopback",
        "::ffff:169.254.169.254, IPv4-mapped",
        "::ffff:0:a9fe:a9fe,     IPv4-translated",
        "::169.254.169.254,      IPv4-compatible",
        "64:ff9b::a9fe:a9fe,     NAT64",
        "2001:0:4136:e378::1,    Teredo",
        "2001:db8::1,            documentation",
        "2002:a9fe:a9fe::1,      6to4",
        "fd00:ec2::254,          'unique local, including cloud metadata'",
        "fe80::1,                link-local",
        "ff02::1,                multicast",
        "4000::1,                outside global unicast",
        "e000::1,                outside global unicast",
    })
    void deniesSpecialIpv6(String address, String name) {
        assertThat(SpecialAddresses.find(ipv6(address))).hasValueSatisfying(range -> assertThat(range.name()).isEqualTo(name));
    }

    /** Neighbours of special blocks, and well-known public resolvers. */
    @ParameterizedTest
    @ValueSource(strings = {
        "1.1.1.1", "8.8.8.8", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255",
        "128.0.0.0", "169.253.255.255", "169.255.0.0", "172.15.255.255", "172.32.0.0", "192.0.1.0",
        "192.167.255.255", "192.169.0.0", "223.255.255.255",
    })
    void allowsPublicIpv4(String address) {
        assertThat(SpecialAddresses.find(ipv4(address))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2606:4700:4700::1111", "2001:4860:4860::8888", "2a00:1450:4001::1", "2001:200::1"})
    void allowsPublicIpv6(String address) {
        assertThat(SpecialAddresses.find(ipv6(address))).isEmpty();
    }
}
