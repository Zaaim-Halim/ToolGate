package io.toolgate.validate;

import java.util.HexFormat;

/**
 * An IP address held as plain bits, so that comparing and masking never touches the JDK's
 * {@code InetAddress}, whose factory methods may resolve names.
 */
public sealed interface IpAddress permits IpAddress.V4, IpAddress.V6 {

    /** The number of bits in addresses of this family. */
    int bitLength();

    /**
     * An IPv4 address.
     *
     * @param bits the 32 address bits, most significant octet first
     */
    record V4(int bits) implements IpAddress {

        static final int BIT_LENGTH = 32;

        @Override
        public int bitLength() {
            return BIT_LENGTH;
        }

        /** Dotted-quad form, such as {@code 169.254.169.254}. */
        @Override
        public String toString() {
            return (bits >>> 24) + "." + ((bits >>> 16) & 0xff) + "." + ((bits >>> 8) & 0xff) + "." + (bits & 0xff);
        }
    }

    /**
     * An IPv6 address.
     *
     * @param high the first 64 address bits
     * @param low  the last 64 address bits
     */
    record V6(long high, long low) implements IpAddress {

        static final int BIT_LENGTH = 128;

        @Override
        public int bitLength() {
            return BIT_LENGTH;
        }

        /** Eight full groups without compression, such as {@code fd00:0ec2:0000:…:0254}; unambiguous in logs. */
        @Override
        public String toString() {
            String hex = HexFormat.of().toHexDigits(high) + HexFormat.of().toHexDigits(low);
            var text = new StringBuilder(39);
            for (int group = 0; group < 8; group++) {
                if (group > 0) {
                    text.append(':');
                }
                text.append(hex, group * 4, group * 4 + 4);
            }
            return text.toString();
        }
    }
}
