package io.toolgate.approvals.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code toolgate.approvals.ui.*}.
 *
 * @param enabled     whether the console and its API exist; off by default, so adding the module exposes
 *                    nothing until the application turns it on
 * @param application the application's name, shown in the console's header so reviewers know whose calls
 *                    they decide
 */
@ConfigurationProperties("toolgate.approvals.ui")
public record ConsoleProperties(boolean enabled, @DefaultValue("ToolGate") String application) {}
