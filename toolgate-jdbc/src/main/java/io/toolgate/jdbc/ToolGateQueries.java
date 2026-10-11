package io.toolgate.jdbc;

import io.toolgate.api.ApprovalStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Reads ToolGate's tables for the application's own screens, reports and monitoring: how approvals are
 * going, and the audit trail.
 *
 * <p><b>For operators, not reviewers.</b> Nothing here is filtered by viewer or tenant, like
 * {@code ToolGate.pending()}: show it only to people who may see every tenant, or filter it yourself.
 * Reviewers see their calls through {@code ToolGate.pending(viewer)} and the console.
 *
 * <p>Approval figures come from the {@code toolgate_v1_*} views, so they work on any of the shipped
 * databases and never read a sealed value. The audit trail comes from the {@link JdbcAuditSink}'s table
 * and includes each event's JSON line, which can quote argument values in reasons and comments.
 */
public final class ToolGateQueries {

    /** The most audit rows one query returns. */
    public static final int MAX_AUDIT_ROWS = 1000;

    private final DataSource dataSource;
    private final Clock clock;

    /**
     * Queries over the tables and views the shipped DDL creates, timed by the system clock.
     *
     * @param dataSource where the tables are
     */
    public ToolGateQueries(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    /**
     * Queries over the tables and views the shipped DDL creates.
     *
     * @param dataSource where the tables are
     * @param clock      what "now" is, for the window and the oldest waiting call
     */
    public ToolGateQueries(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * How approvals are going: what waits now, and what became of the calls deferred in the window.
     *
     * @param window how far back to count deferred calls, such as 7 days
     * @return the figures, as of now
     * @throws IllegalArgumentException if the window is not positive
     */
    public ApprovalStats stats(Duration window) {
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("window must be positive, was " + window);
        }
        Instant now = clock.instant();
        long since = Codec.micros(now.minus(window));
        try (Connection connection = dataSource.getConnection()) {
            int waiting = 0;
            int unknown = 0;
            Duration oldest = Duration.ZERO;
            try (PreparedStatement select = connection.prepareStatement("SELECT status, COUNT(*), MIN(deferred_at)"
                    + " FROM toolgate_v1_approvals WHERE status IN ('PENDING', 'UNKNOWN') GROUP BY status");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    if ("PENDING".equals(rows.getString(1))) {
                        waiting = rows.getInt(2);
                        oldest = maxZero(Duration.between(Codec.instant(rows.getLong(3)), now));
                    } else {
                        unknown = rows.getInt(2);
                    }
                }
            }
            var byStatus = new EnumMap<ApprovalStatus, Integer>(ApprovalStatus.class);
            try (PreparedStatement select = connection.prepareStatement("SELECT status, COUNT(*)"
                    + " FROM toolgate_v1_approvals WHERE deferred_at >= ? GROUP BY status")) {
                select.setLong(1, since);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        byStatus.put(ApprovalStatus.valueOf(rows.getString(1)), rows.getInt(2));
                    }
                }
            }
            // Time to decide: from the deferral to a reviewer's first approval or rejection. Withdrawals,
            // expiries and resolutions say nothing about how fast reviewers are.
            var latencies = new ArrayList<Long>();
            try (PreparedStatement select = connection.prepareStatement("SELECT MIN(d.decided_at) - a.deferred_at"
                    + " FROM toolgate_v1_approvals a JOIN toolgate_v1_approval_decisions d ON d.approval_id = a.approval_id"
                    + " WHERE a.deferred_at >= ? AND d.kind IN ('APPROVE', 'REJECT') GROUP BY a.approval_id, a.deferred_at")) {
                select.setLong(1, since);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        latencies.add(Math.max(0, rows.getLong(1)));
                    }
                }
            }
            Collections.sort(latencies);
            return new ApprovalStats(now, window, waiting, oldest, unknown, byStatus,
                    percentile(latencies, 50), percentile(latencies, 95), latencies.size());
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not read approval figures", failure);
        }
    }

    /**
     * Audit rows matching the query, newest first.
     *
     * @param query which rows, and how many
     * @return the rows, at most the query's limit
     */
    public List<AuditRow> audit(AuditQuery query) {
        Objects.requireNonNull(query, "query");
        var where = new ArrayList<String>();
        var values = new ArrayList<Object>();
        if (!query.kinds.isEmpty()) {
            where.add("kind IN (" + String.join(", ", Collections.nCopies(query.kinds.size(), "?")) + ")");
            values.addAll(query.kinds);
        }
        equal(where, values, "session_id", query.sessionId);
        equal(where, values, "approval_id", query.approvalId);
        equal(where, values, "call_id", query.callId);
        equal(where, values, "agent_id", query.agentId);
        equal(where, values, "actor_id", query.actorId);
        equal(where, values, "tool", query.tool);
        equal(where, values, "outcome", query.outcome);
        if (query.since != null) {
            where.add("recorded_at >= ?");
            values.add(Codec.micros(query.since));
        }
        if (query.until != null) {
            where.add("recorded_at < ?");
            values.add(Codec.micros(query.until));
        }
        String sql = "SELECT event_id, recorded_at, kind, call_id, approval_id, session_id, agent_id, actor_id, tenant,"
                + " tool, outcome, rule_id, mode, policy_hash, event FROM " + JdbcAuditSink.TABLE
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY recorded_at DESC, event_id DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (Object value : values) {
                if (value instanceof Long micros) {
                    select.setLong(parameter++, micros);
                } else {
                    select.setString(parameter++, (String) value);
                }
            }
            select.setInt(parameter, query.limit);
            try (ResultSet rows = select.executeQuery()) {
                var found = new ArrayList<AuditRow>();
                while (rows.next()) {
                    found.add(new AuditRow(rows.getString("event_id"), Codec.instant(rows.getLong("recorded_at")),
                            rows.getString("kind"), rows.getString("call_id"), rows.getString("approval_id"),
                            rows.getString("session_id"), rows.getString("agent_id"), rows.getString("actor_id"),
                            rows.getString("tenant"), rows.getString("tool"), rows.getString("outcome"),
                            rows.getString("rule_id"), rows.getString("mode"), rows.getString("policy_hash"),
                            rows.getString("event")));
                }
                return found;
            }
        } catch (SQLException failure) {
            throw new JdbcStoreException("could not read the audit trail", failure);
        }
    }

    private static void equal(List<String> where, List<Object> values, String column, String value) {
        if (value != null) {
            where.add(column + " = ?");
            values.add(value);
        }
    }

    /** Nearest-rank percentile over sorted microseconds; zero without samples. */
    private static Duration percentile(List<Long> sorted, int percent) {
        if (sorted.isEmpty()) {
            return Duration.ZERO;
        }
        int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
        return Duration.ofNanos(sorted.get(Math.clamp(rank - 1, 0, sorted.size() - 1)) * 1_000);
    }

    private static Duration maxZero(Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    /**
     * How approvals are going.
     *
     * @param at             when the figures were read
     * @param window         how far back deferred calls were counted
     * @param waiting        calls waiting for a decision now, deferred at any time
     * @param oldestWaiting  how long the oldest waiting call has waited; zero when none waits
     * @param unknown        calls whose run has an unknown outcome now, waiting to be resolved
     * @param byStatus       the calls deferred in the window, by their status now
     * @param medianLatency  median time from deferral to a reviewer's first approval or rejection, for calls
     *                       deferred in the window; zero without samples
     * @param p95Latency     the same, 95th percentile
     * @param latencySamples how many calls the latencies cover
     */
    public record ApprovalStats(Instant at, Duration window, int waiting, Duration oldestWaiting, int unknown,
                                Map<ApprovalStatus, Integer> byStatus, Duration medianLatency, Duration p95Latency,
                                int latencySamples) {

        /** Requires every component and copies the counts. */
        public ApprovalStats {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(oldestWaiting, "oldestWaiting");
            byStatus = byStatus.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(byStatus));
            Objects.requireNonNull(medianLatency, "medianLatency");
            Objects.requireNonNull(p95Latency, "p95Latency");
        }

        /**
         * The calls deferred in the window that now have a status.
         *
         * @param status the status
         * @return how many; zero when none
         */
        public int count(ApprovalStatus status) {
            return byStatus.getOrDefault(status, 0);
        }
    }

    /**
     * One audit event as stored. Columns that do not apply to the event's kind are null.
     *
     * @param eventId    the row's ID
     * @param at         when the event happened
     * @param kind       {@code DECIDED}, {@code WITHHELD}, {@code APPROVAL_DECIDED}, {@code APPROVAL_RECORDED},
     *                   {@code APPROVAL_VIEWED}, {@code APPROVALS_LISTED}, {@code SUPER_ADMIN_BURST},
     *                   {@code DECLASSIFIED} or {@code POLICY_LOADED}
     * @param callId     the tool call, for gate decisions and withheld results
     * @param approvalId the deferred call, for deferrals and approval events
     * @param sessionId  the session
     * @param agentId    the agent
     * @param actorId    who acted: the principal of a call, the reviewer of a decision, the viewer of a listing
     * @param tenant     the principal's tenant, for gate decisions and withheld results
     * @param tool       the tool
     * @param outcome    what happened, as the audit log names it: {@code allow}, {@code deny}, {@code defer},
     *                   {@code downgrade}, {@code terminate}; an approval's status, such as {@code released}
     *                   or {@code rejected}; {@code withheld}, {@code viewed}, {@code listed} and so on
     * @param ruleId     the rule that decided, for gate decisions and withheld results
     * @param mode       {@code enforce} or {@code monitor} for gate decisions; the access for approval events,
     *                   such as {@code reviewer} or {@code super_admin}
     * @param policyHash the policy that decided
     * @param event      the event's JSON line, as the audit log has it
     */
    public record AuditRow(String eventId, Instant at, String kind, String callId, String approvalId, String sessionId,
                           String agentId, String actorId, String tenant, String tool, String outcome, String ruleId,
                           String mode, String policyHash, String event) {

        /** Requires the columns every row has. */
        public AuditRow {
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(event, "event");
        }
    }

    /**
     * Which audit rows to read: every filter left out matches everything. Immutable; each method returns
     * a new query.
     */
    public static final class AuditQuery {

        private static final int DEFAULT_LIMIT = 100;

        private final Set<String> kinds;
        private final String sessionId;
        private final String approvalId;
        private final String callId;
        private final String agentId;
        private final String actorId;
        private final String tool;
        private final String outcome;
        private final Instant since;
        private final Instant until;
        private final int limit;

        private AuditQuery(Set<String> kinds, String sessionId, String approvalId, String callId, String agentId,
                           String actorId, String tool, String outcome, Instant since, Instant until, int limit) {
            this.kinds = kinds;
            this.sessionId = sessionId;
            this.approvalId = approvalId;
            this.callId = callId;
            this.agentId = agentId;
            this.actorId = actorId;
            this.tool = tool;
            this.outcome = outcome;
            this.since = since;
            this.until = until;
            this.limit = limit;
        }

        /**
         * The newest rows of every kind, at most 100.
         *
         * @return the query
         */
        public static AuditQuery latest() {
            return new AuditQuery(Set.of(), null, null, null, null, null, null, null, null, null, DEFAULT_LIMIT);
        }

        /**
         * Only rows of these kinds, such as {@code DECIDED} or {@code APPROVAL_DECIDED}.
         *
         * @param kinds the kinds, as {@link AuditRow#kind()} names them
         * @return the narrowed query
         */
        public AuditQuery kinds(String... kinds) {
            var set = new LinkedHashSet<String>();
            for (String kind : kinds) {
                set.add(Objects.requireNonNull(kind, "kind"));
            }
            return new AuditQuery(Set.copyOf(set), sessionId, approvalId, callId, agentId, actorId, tool, outcome,
                    since, until, limit);
        }

        /**
         * Only rows of one session.
         *
         * @param sessionId the session
         * @return the narrowed query
         */
        public AuditQuery session(String sessionId) {
            return new AuditQuery(kinds, required(sessionId, "sessionId"), approvalId, callId, agentId, actorId, tool,
                    outcome, since, until, limit);
        }

        /**
         * Only rows about one deferred call: its deferral and every approval event.
         *
         * @param approvalId the call's approval ID
         * @return the narrowed query
         */
        public AuditQuery approval(String approvalId) {
            return new AuditQuery(kinds, sessionId, required(approvalId, "approvalId"), callId, agentId, actorId, tool,
                    outcome, since, until, limit);
        }

        /**
         * Only rows about one tool call.
         *
         * @param callId the call
         * @return the narrowed query
         */
        public AuditQuery call(String callId) {
            return new AuditQuery(kinds, sessionId, approvalId, required(callId, "callId"), agentId, actorId, tool,
                    outcome, since, until, limit);
        }

        /**
         * Only rows of one agent.
         *
         * @param agentId the agent
         * @return the narrowed query
         */
        public AuditQuery agent(String agentId) {
            return new AuditQuery(kinds, sessionId, approvalId, callId, required(agentId, "agentId"), actorId, tool,
                    outcome, since, until, limit);
        }

        /**
         * Only rows where one person or principal acted.
         *
         * @param actorId their ID
         * @return the narrowed query
         */
        public AuditQuery actor(String actorId) {
            return new AuditQuery(kinds, sessionId, approvalId, callId, agentId, required(actorId, "actorId"), tool,
                    outcome, since, until, limit);
        }

        /**
         * Only rows about one tool.
         *
         * @param tool the tool, such as {@code local/issueRefund}
         * @return the narrowed query
         */
        public AuditQuery tool(String tool) {
            return new AuditQuery(kinds, sessionId, approvalId, callId, agentId, actorId, required(tool, "tool"),
                    outcome, since, until, limit);
        }

        /**
         * Only rows with one outcome, such as {@code deny} or {@code rejected}.
         *
         * @param outcome the outcome, as {@link AuditRow#outcome()} names it
         * @return the narrowed query
         */
        public AuditQuery outcome(String outcome) {
            return new AuditQuery(kinds, sessionId, approvalId, callId, agentId, actorId, tool,
                    required(outcome, "outcome"), since, until, limit);
        }

        /**
         * Only rows recorded at or after an instant.
         *
         * @param since the earliest instant
         * @return the narrowed query
         */
        public AuditQuery since(Instant since) {
            return new AuditQuery(kinds, sessionId, approvalId, callId, agentId, actorId, tool, outcome,
                    Objects.requireNonNull(since, "since"), until, limit);
        }

        /**
         * Only rows recorded before an instant.
         *
         * @param until the instant rows must precede
         * @return the narrowed query
         */
        public AuditQuery until(Instant until) {
            return new AuditQuery(kinds, sessionId, approvalId, callId, agentId, actorId, tool, outcome, since,
                    Objects.requireNonNull(until, "until"), limit);
        }

        /**
         * At most this many rows.
         *
         * @param limit 1 to {@link ToolGateQueries#MAX_AUDIT_ROWS}
         * @return the query
         * @throws IllegalArgumentException if the limit is out of range
         */
        public AuditQuery limit(int limit) {
            if (limit < 1 || limit > MAX_AUDIT_ROWS) {
                throw new IllegalArgumentException("limit must be 1 to " + MAX_AUDIT_ROWS + ", was " + limit);
            }
            return new AuditQuery(kinds, sessionId, approvalId, callId, agentId, actorId, tool, outcome, since, until,
                    limit);
        }

        private static String required(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
