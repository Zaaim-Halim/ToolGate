/**
 * ToolGate core: decides whether each tool call an AI agent makes may run, and records why.
 *
 * <p>Only the {@code api} and {@code spi} packages are exported. Everything else is internal so it
 * can change freely before 1.0.
 */
module io.toolgate.core {
    exports io.toolgate.api;
}
