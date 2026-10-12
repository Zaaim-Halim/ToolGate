package io.toolgate.spi;

import static io.toolgate.support.Checks.requireNonBlank;

import io.toolgate.api.Decision;
import io.toolgate.api.Label;
import io.toolgate.api.ToolId;
import io.toolgate.spi.SessionEvent.CallDecided;
import io.toolgate.spi.SessionEvent.CallReleased;
import io.toolgate.spi.SessionEvent.CallResolved;
import io.toolgate.spi.SessionEvent.CallUnresolved;
import io.toolgate.spi.SessionEvent.Declassified;
import io.toolgate.spi.SessionEvent.ResultRecorded;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * An immutable snapshot of one session, built by folding its events in order.
 *
 * <p>Stores keep events or snapshots as they prefer, but must produce exactly what folding the
 * events with {@link #apply} produces, so that every store makes the same decisions.
 *
 * @param sessionId the session
 * @param version   the number of events applied; stores use it for optimistic concurrency
 * @param toolCalls calls that used tool-call budget: allowed, deferred or downgraded ones
 * @param denials   calls that were denied, for the denial budget
 * @param taint     the source labels the session holds, each with the call that first brought it in
 * @param totals    the sums that total limits watch, by rule ID: what allowed and released calls added
 * @param unresolved the approved calls whose outcome is unknown, by approval ID, with their tools
 * @param trusted   fingerprints of trusted field values that bound arguments may take, by
 *                  {@code Binding.sourceKey}: only from outputs received before the session first held untrusted
 *                  content, and the output that brought it in; at most {@link #MAX_TRUSTED_VALUES} per field
 * @param trustClosed whether the session has ever held untrusted content: from then on no output adds trusted
 *                  values, even after the label is declassified, since the model has read that content
 */
public record SessionState(String sessionId, long version, int toolCalls, int denials, Map<Label, TaintSource> taint,
                           Map<String, BigDecimal> totals, Map<String, ToolId> unresolved,
                           Map<String, Set<String>> trusted, boolean trustClosed) {

    /** Values kept per trusted field; later ones do not count, so a long list cannot grow a session without bound. */
    public static final int MAX_TRUSTED_VALUES = 100;

    /** Rejects negative counters, which only a corrupt store could produce. */
    public SessionState {
        requireNonBlank(sessionId, "sessionId");
        if (version < 0 || toolCalls < 0 || denials < 0) {
            throw new IllegalArgumentException("session counters must not be negative");
        }
        taint = taint.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(taint));
        totals = Totals.copy(totals);
        unresolved = Collections.unmodifiableMap(new TreeMap<>(unresolved));
        var copied = new TreeMap<String, Set<String>>();
        trusted.forEach((key, values) -> copied.put(key, Collections.unmodifiableSet(new TreeSet<>(values))));
        trusted = Collections.unmodifiableMap(copied);
    }

    /**
     * A state with no trusted values.
     *
     * @param sessionId  the session
     * @param version    the number of events applied
     * @param toolCalls  calls that used tool-call budget
     * @param denials    calls that were denied
     * @param taint      the source labels the session holds
     * @param totals     the sums that total limits watch
     * @param unresolved the approved calls whose outcome is unknown
     */
    public SessionState(String sessionId, long version, int toolCalls, int denials, Map<Label, TaintSource> taint,
                        Map<String, BigDecimal> totals, Map<String, ToolId> unresolved) {
        this(sessionId, version, toolCalls, denials, taint, totals, unresolved, Map.of(), taint.containsKey(Label.UNTRUSTED_CONTENT));
    }

    /**
     * A state with no unresolved calls.
     *
     * @param sessionId the session
     * @param version   the number of events applied
     * @param toolCalls calls that used tool-call budget
     * @param denials   calls that were denied
     * @param taint     the source labels the session holds
     * @param totals    the sums that total limits watch
     */
    public SessionState(String sessionId, long version, int toolCalls, int denials, Map<Label, TaintSource> taint,
                        Map<String, BigDecimal> totals) {
        this(sessionId, version, toolCalls, denials, taint, totals, Map.of());
    }

    /**
     * The state of a session that has no events yet.
     *
     * @param sessionId the session
     * @return the state at version zero
     */
    public static SessionState empty(String sessionId) {
        return new SessionState(sessionId, 0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of(), false);
    }

    /**
     * The state after one more event.
     *
     * @param event an event of this session
     * @return the new state, one version later
     */
    public SessionState apply(SessionEvent event) {
        if (!event.sessionId().equals(sessionId)) {
            throw new IllegalArgumentException(
                    "event for session " + event.sessionId() + " applied to session " + sessionId);
        }
        return switch (event) {
            case CallDecided decided -> switch (decided.applied()) {
                // A denied call releases the budget it would have used, so probing costs denials, not calls.
                case Decision.Allow allowed -> next(toolCalls + 1, denials, taint, Totals.add(totals, decided.spent()));
                case Decision.Defer deferred -> next(toolCalls + 1, denials, taint, totals);
                case Decision.Downgrade downgraded -> next(toolCalls + 1, denials, taint, totals);
                case Decision.Deny denied -> next(toolCalls, denials + 1, taint, totals);
                case Decision.Terminate terminated -> next(toolCalls, denials, taint, totals);
            };
            case CallReleased released -> next(toolCalls, denials, taint, Totals.add(totals, released.spent()));
            // A withheld output counts as a denial, so probing through search results ends the run too.
            case ResultRecorded recorded -> recorded.withheld()
                    ? next(toolCalls, denials + 1, taint, totals)
                    : withTrusted(recorded).next(toolCalls, denials, withLabels(recorded), totals);
            case Declassified declassified -> next(toolCalls, denials, without(declassified.label()), totals);
            case CallUnresolved lost -> withUnresolved(lost.approvalId(), lost.tool());
            case CallResolved known -> withUnresolved(known.approvalId(), null);
        };
    }

    /**
     * Whether the session holds a label.
     *
     * @param label a source label
     * @return {@code true} if some call brought it in and it was not declassified since
     */
    public boolean holds(Label label) {
        return taint.containsKey(label);
    }

    /**
     * What a total limit sees so far.
     *
     * @param ruleId the rule ID of the argument, such as {@code issueRefund.amount}
     * @return the sum of what allowed and released calls added; zero if nothing yet
     */
    public BigDecimal total(String ruleId) {
        return totals.getOrDefault(ruleId, BigDecimal.ZERO);
    }

    /**
     * The unresolved call of a tool, if the session has one.
     *
     * @param tool the tool
     * @return the approval ID of a call of that tool whose outcome is unknown, or empty
     */
    public Optional<String> unresolvedCallOf(ToolId tool) {
        return unresolved.entrySet().stream().filter(entry -> entry.getValue().equals(tool)).map(Map.Entry::getKey).findFirst();
    }

    /**
     * Whether a bound argument may take a value.
     *
     * @param sourceKey   the trusted field, as {@code Binding.sourceKey} names it
     * @param fingerprint the value's fingerprint
     * @return {@code true} if the session received that value in that field while it could trust it
     */
    public boolean trusts(String sourceKey, String fingerprint) {
        return trusted.getOrDefault(sourceKey, Set.of()).contains(fingerprint);
    }

    private SessionState next(int newToolCalls, int newDenials, Map<Label, TaintSource> newTaint,
                              Map<String, BigDecimal> newTotals) {
        return new SessionState(sessionId, version + 1, newToolCalls, newDenials, newTaint, newTotals, unresolved, trusted,
                trustClosed || newTaint.containsKey(Label.UNTRUSTED_CONTENT));
    }

    /**
     * Keeps a result's trusted values if the session did not yet hold untrusted content: after that, the
     * model may have been steered into reading a record whose trusted fields an outsider chose.
     */
    private SessionState withTrusted(ResultRecorded recorded) {
        if (recorded.trusted().isEmpty() || trustClosed || holds(Label.UNTRUSTED_CONTENT)) {
            return this;
        }
        var updated = new TreeMap<String, Set<String>>(trusted);
        recorded.trusted().forEach((key, values) -> {
            var kept = new TreeSet<>(updated.getOrDefault(key, Set.of()));
            // One output arrives capped in its own order; across outputs the earlier ones are kept, then by fingerprint.
            for (String value : new TreeSet<>(values)) {
                if (kept.size() >= MAX_TRUSTED_VALUES) {
                    break;
                }
                kept.add(value);
            }
            updated.put(key, kept);
        });
        return new SessionState(sessionId, version, toolCalls, denials, taint, totals, unresolved, updated, trustClosed);
    }

    /** One more unresolved call, or one fewer when {@code tool} is null. */
    private SessionState withUnresolved(String approvalId, ToolId tool) {
        var updated = new TreeMap<>(unresolved);
        if (tool == null) {
            updated.remove(approvalId);
        } else {
            updated.put(approvalId, tool);
        }
        return new SessionState(sessionId, version + 1, toolCalls, denials, taint, totals, updated, trusted, trustClosed);
    }

    /** The first source of each label is kept: it is where the session became tainted. */
    private Map<Label, TaintSource> withLabels(ResultRecorded recorded) {
        var updated = new EnumMap<Label, TaintSource>(Label.class);
        updated.putAll(taint);
        recorded.labels().forEach(label -> updated.putIfAbsent(label, new TaintSource(recorded.callId(), recorded.tool())));
        return updated;
    }

    private Map<Label, TaintSource> without(Label label) {
        var updated = new EnumMap<Label, TaintSource>(Label.class);
        updated.putAll(taint);
        updated.remove(label);
        return updated;
    }
}
