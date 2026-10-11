package io.toolgate.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code toolgate.*}.
 *
 * @param agent the agent ID for requests whose tool context names none, for an application with one
 *              agent; {@link ToolGateContext} overrides it per request
 * @param audit where decisions are recorded, under {@code toolgate.audit.*}
 */
@ConfigurationProperties("toolgate")
public record ToolGateProperties(String agent, @DefaultValue Audit audit) {

    /**
     * Where decisions are recorded, besides any {@code AuditSink} or {@code DecisionObserver} beans.
     *
     * @param log           {@code toolgate.audit.log.enabled}: one JSON line per event on the
     *                      {@code toolgate.audit} logger; on by default
     * @param micrometer    {@code toolgate.audit.micrometer.enabled}: decision counter, check timer and
     *                      dropped-events counter in the application's {@code MeterRegistry}; off by default
     * @param opentelemetry {@code toolgate.audit.opentelemetry.enabled}: decisions on the current span;
     *                      off by default
     */
    public record Audit(@DefaultValue Log log, @DefaultValue Toggle micrometer, @DefaultValue Toggle opentelemetry) {}

    /**
     * The {@code toolgate.audit} log.
     *
     * @param enabled whether it is on; on by default
     */
    public record Log(@DefaultValue("true") boolean enabled) {}

    /**
     * Turns an optional integration on.
     *
     * @param enabled whether it is on; off by default
     */
    public record Toggle(boolean enabled) {}
}
