package io.toolgate.jdbc;

import io.toolgate.spi.ValueCodec;
import java.lang.System.Logger.Level;

/** The default for stores built without a codec: values in clear, said once in the log so nobody misses it. */
final class Unsealed {

    private static final System.Logger LOG = System.getLogger("io.toolgate.jdbc");

    private Unsealed() {}

    static ValueCodec warnOnce(String table) {
        LOG.log(Level.WARNING, () -> table + ": ToolGate stores argument values unencrypted; pass a ValueCodec to seal them,"
                + " or ValueCodec.unencrypted() to choose clear text and silence this warning");
        return ValueCodec.unencrypted();
    }
}
