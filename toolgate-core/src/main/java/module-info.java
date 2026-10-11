/**
 * ToolGate core: decides whether each tool call an AI agent makes may run, and records why.
 *
 * <p>Only the {@code api}, {@code spi} and {@code telemetry} packages are exported. Everything else is
 * internal so it can change freely before 1.0.
 *
 * <p>Micrometer and OpenTelemetry are optional: only {@code io.toolgate.telemetry} uses them, and only
 * when the application adds one of its classes to a gate. Neither ships a module descriptor, so they
 * are required by their automatic module names.
 */
@SuppressWarnings("requires-automatic")
module io.toolgate.core {
    exports io.toolgate.api;
    exports io.toolgate.api.policy;
    exports io.toolgate.spi;
    exports io.toolgate.telemetry;

    requires static micrometer.core;
    requires static io.opentelemetry.api;
    requires static io.opentelemetry.context;
}
