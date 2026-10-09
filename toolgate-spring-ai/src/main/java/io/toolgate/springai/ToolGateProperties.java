package io.toolgate.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code toolgate.*}.
 *
 * @param agent the agent ID for requests whose tool context names none, for an application with one
 *              agent; {@link ToolGateContext} overrides it per request
 */
@ConfigurationProperties("toolgate")
public record ToolGateProperties(String agent) {}
