package io.toolgate.springai;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code toolgate.*}.
 *
 * @param agent the agent ID for requests whose tool context names none, for an application with one
 *              agent; {@link ToolGateContext} overrides it per request
 * @param audit     where decisions are recorded, under {@code toolgate.audit.*}
 * @param approvals how approved calls are run, under {@code toolgate.approvals.*}
 */
@ConfigurationProperties("toolgate")
public record ToolGateProperties(String agent, @DefaultValue Audit audit, @DefaultValue Approvals approvals) {

    /**
     * Settings under {@code toolgate.approvals.*}.
     *
     * @param executor the executor that runs approved calls
     */
    public record Approvals(@DefaultValue Executor executor) {}

    /**
     * The executor that runs released calls, under {@code toolgate.approvals.executor.*}. Each instance
     * claims a call before running it, so in a cluster each call runs once.
     *
     * @param enabled  whether to run calls approved elsewhere, and sweep the queue, on a schedule; on by default
     * @param id       this instance's name in the queue; defaults to the host name and process ID
     * @param lease    how long a run may take before its outcome counts as lost; 5 minutes by default
     * @param interval how often to sweep and look for calls to run; 30 seconds by default
     */
    public record Executor(@DefaultValue("true") boolean enabled, String id, @DefaultValue("5m") Duration lease,
                           @DefaultValue("30s") Duration interval) {

        /**
         * This instance's executor name.
         *
         * @return the configured ID, or the host name and process ID
         */
        public String resolvedId() {
            if (id != null && !id.isBlank()) {
                return id;
            }
            String host;
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException unknown) {
                host = "unknown-host";
            }
            return host + "-" + ProcessHandle.current().pid();
        }
    }

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
