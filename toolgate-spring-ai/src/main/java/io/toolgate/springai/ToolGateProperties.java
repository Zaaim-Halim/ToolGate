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
 * @param jdbc      how long ToolGate's JDBC tables keep rows, under {@code toolgate.jdbc.*}
 */
@ConfigurationProperties("toolgate")
public record ToolGateProperties(String agent, @DefaultValue Audit audit, @DefaultValue Approvals approvals,
                                 @DefaultValue Jdbc jdbc) {

    /**
     * Retention for ToolGate's JDBC tables, under {@code toolgate.jdbc.*}. Nothing is deleted unless
     * {@code retention} is set.
     *
     * @param retention        {@code toolgate.jdbc.retention}: how long audit rows and finished approvals are
     *                         kept, such as {@code 400d}; unset by default, which keeps everything
     * @param sessionRetention {@code toolgate.jdbc.session-retention}: how long an idle session and its events,
     *                         which hold real argument values, are kept; defaults to {@code retention}
     * @param purgeInterval    {@code toolgate.jdbc.purge-interval}: how often to purge; daily by default
     */
    public record Jdbc(Duration retention, Duration sessionRetention, @DefaultValue("1d") Duration purgeInterval) {}

    /**
     * Settings under {@code toolgate.approvals.*}.
     *
     * @param executor the executor that runs approved calls
     * @param outcome  how the agent learns what became of the calls it asked for
     * @param metrics  the approval gauges, under {@code toolgate.approvals.metrics.*}
     */
    public record Approvals(@DefaultValue Executor executor, @DefaultValue Outcome outcome, @DefaultValue Metrics metrics) {}

    /**
     * The approval gauges, on with {@code toolgate.audit.micrometer.enabled}.
     *
     * @param refresh {@code toolgate.approvals.metrics.refresh}: how often the gauges read the approval queue;
     *                30 seconds by default
     */
    public record Metrics(@DefaultValue("30s") Duration refresh) {}

    /**
     * Settings under {@code toolgate.approvals.outcome.*}.
     *
     * @param chatMemory {@code toolgate.approvals.outcome.chat-memory}: add a message ToolGate writes to the
     *                   conversation's chat memory when a deferred call is decided or finishes; off by default
     */
    public record Outcome(boolean chatMemory) {}

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
