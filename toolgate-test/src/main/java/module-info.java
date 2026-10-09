/**
 * Test your ToolGate policies with scripted sessions: call tools as a compromised model would, and
 * assert what ToolGate decides, with no model and no framework.
 */
module io.toolgate.test {
    requires transitive io.toolgate.core;
    requires transitive org.junit.jupiter.api;

    exports io.toolgate.test;
}
