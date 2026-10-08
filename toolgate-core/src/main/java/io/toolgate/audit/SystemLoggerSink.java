package io.toolgate.audit;

import io.toolgate.spi.AuditEvent;
import io.toolgate.spi.AuditSink;
import java.lang.System.Logger.Level;
import java.util.function.Supplier;

/**
 * The default sink: one JSON line per event on the JDK logger {@value #LOGGER_NAME}, at INFO.
 *
 * <p>Spring Boot's default Logback setup routes {@code System.Logger} to SLF4J, so core needs no
 * logging library.
 */
public final class SystemLoggerSink implements AuditSink {

    /** The logger name, for routing and filtering audit lines. */
    public static final String LOGGER_NAME = "toolgate.audit";

    private final System.Logger logger = System.getLogger(LOGGER_NAME);

    @Override
    public void emit(AuditEvent event) {
        // The supplier form skips formatting when INFO is off, and passes no parameters, so no
        // backend applies MessageFormat to the braces in the JSON.
        logger.log(Level.INFO, (Supplier<String>) () -> AuditJson.format(event));
    }
}
