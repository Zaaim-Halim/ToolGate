/**
 * ToolGate core: decides whether each tool call an AI agent makes may run, and records why.
 *
 * <p>Only {@code io.toolgate.api} and {@code io.toolgate.spi} may ever be exported. Everything
 * else is internal so it can change freely before 1.0. The exports are added together with the
 * first types in those packages, because the compiler rejects exporting an empty package.
 */
module io.toolgate.core {
}
