package io.toolgate.engine;

import io.toolgate.api.ApprovalDecision;
import io.toolgate.api.ApprovalStatus;
import io.toolgate.api.Decision;
import io.toolgate.api.Execution;
import io.toolgate.api.Journey;
import io.toolgate.api.Label;
import io.toolgate.spi.PendingCall;
import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.TaintSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the journey of a deferred call from its session's events and its queue record. Only tool names,
 * decisions, rules and labels come from the log: a call's arguments, and the reasons of other calls, which
 * can quote their arguments, are left out. The deferred call's reason is the one its review view shows.
 */
final class Journeys {

    /** A long session is cut to its most recent steps, so a graph stays readable and a response small. */
    static final int MAX_CALLS = 300;

    private Journeys() {}

    static Journey of(PendingCall pending, List<SessionEvent> events, String shownReason) {
        String subjectId = pending.call().callId();
        var calls = new LinkedHashMap<String, MutableStep>();
        var flows = new ArrayList<Journey.Flow>();
        var extra = new ArrayList<Journey.Step>();
        SessionState state = SessionState.empty(pending.call().sessionId());
        for (SessionEvent event : events) {
            switch (event) {
                case SessionEvent.CallDecided decided -> {
                    boolean subject = decided.callId().equals(subjectId);
                    // A call a binding let through went out despite the taint, so its flows are shown as well.
                    boolean taintRule = decided.applied().ruleId().startsWith("taint.")
                            || decided.computed().ruleId().startsWith("taint.")
                            || decided.applied().ruleId().endsWith(".bound")
                            || decided.computed().ruleId().endsWith(".bound");
                    if (subject || taintRule) {
                        for (Map.Entry<Label, TaintSource> source : state.taint().entrySet()) {
                            flows.add(new Journey.Flow(source.getValue().callId(), decided.callId(), source.getKey()));
                        }
                    }
                    calls.put(decided.callId(), new MutableStep(decided, subject, subject ? shownReason : detail(decided.applied())));
                }
                case SessionEvent.ResultRecorded recorded -> {
                    MutableStep step = calls.get(recorded.callId());
                    if (step != null && !recorded.withheld()) {
                        step.labels.addAll(recorded.labels());
                    } else if (step != null) {
                        step.withheld = true;
                    }
                }
                case SessionEvent.Declassified removed -> extra.add(new Journey.Step("declassified-" + extra.size(),
                        Journey.Kind.DECLASSIFIED, removed.at(), "Declassified " + removed.label().name(),
                        "declassified", null, removed.justification(), Set.of(removed.label()), false));
                case SessionEvent.CallReleased released -> { }
                case SessionEvent.CallUnresolved lost -> { }
                case SessionEvent.CallResolved known -> { }
            }
            state = state.apply(event);
        }
        boolean complete = calls.containsKey(subjectId);
        if (!complete) {
            // The store keeps no event log, or it was purged: the queue still knows the deferred call.
            calls.put(subjectId, new MutableStep(subjectId, pending.call().tool().toString(), pending.deferredAt(),
                    pending.deferral(), shownReason));
        }
        var steps = new ArrayList<Journey.Step>();
        List<MutableStep> kept = new ArrayList<>(calls.values());
        if (kept.size() > MAX_CALLS) {
            kept = kept.subList(kept.size() - MAX_CALLS, kept.size());
        }
        var keptIds = new HashSet<String>();
        for (MutableStep step : kept) {
            steps.add(step.build());
            keptIds.add(step.id);
        }
        steps.addAll(extra);
        int person = 0;
        for (ApprovalDecision decision : pending.decisions()) {
            steps.add(new Journey.Step("person-" + person++, Journey.Kind.PERSON, decision.at(),
                    decision.by() + " (" + decision.access().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")",
                    decision.kind().name().toLowerCase(Locale.ROOT), null, decision.comment(), Set.of(), false));
        }
        run(pending).ifPresent(steps::add);
        steps.sort(Comparator.comparing(Journey.Step::at));
        List<Journey.Flow> keptFlows = flows.stream()
                .filter(flow -> keptIds.contains(flow.from()) && keptIds.contains(flow.to())).distinct().toList();
        return new Journey(pending.approvalId(), pending.call().sessionId(), pending.call().agentId(), steps, keptFlows,
                complete && calls.size() <= MAX_CALLS);
    }

    /** How the call ran, once approved; executors' output summaries are left out, since they can carry data. */
    private static Optional<Journey.Step> run(PendingCall pending) {
        Execution execution = pending.execution();
        String title = switch (pending.status()) {
            case RELEASED -> "About to run";
            case EXECUTING -> "Running";
            case EXECUTED -> "Ran";
            case FAILED -> "Failed when run";
            case UNKNOWN -> "Outcome unknown";
            case REFUSED -> "Refused on re-check";
            case EXPIRED -> "Expired undecided";
            default -> null;
        };
        if (title == null) {
            return Optional.empty();
        }
        Instant at = execution == null ? lastDecisionOr(pending)
                : execution.finishedAt() != null ? execution.finishedAt() : execution.claimedAt();
        if (pending.status() == ApprovalStatus.EXPIRED) {
            at = pending.expiresAt();
        }
        return Optional.of(new Journey.Step("run", Journey.Kind.RUN, at, title,
                pending.status().name().toLowerCase(Locale.ROOT), null,
                execution == null ? null : "by " + execution.executor(), Set.of(), false));
    }

    private static Instant lastDecisionOr(PendingCall pending) {
        return pending.decisions().isEmpty() ? pending.deferredAt() : pending.decisions().getLast().at();
    }

    /** What may be said of a call that is not the subject: never its reason, which can quote its arguments. */
    private static String detail(Decision applied) {
        return applied instanceof Decision.Downgrade downgrade ? "ran " + downgrade.safeTool() + " instead" : null;
    }

    private static String outcome(Decision decision) {
        return switch (decision) {
            case Decision.Allow allow -> "allow";
            case Decision.Deny deny -> "deny";
            case Decision.Defer defer -> "defer";
            case Decision.Downgrade downgrade -> "downgrade";
            case Decision.Terminate terminate -> "terminate";
        };
    }

    /** A call step while its result is still being folded in. */
    private static final class MutableStep {

        final String id;
        final String title;
        final Instant at;
        final String outcome;
        final String ruleId;
        final String detail;
        final boolean subject;
        final Set<Label> labels = EnumSet.noneOf(Label.class);
        boolean withheld;

        MutableStep(SessionEvent.CallDecided decided, boolean subject, String detail) {
            this(decided.callId(), decided.tool().toString(), decided.at(), outcome(decided.applied()),
                    decided.applied().ruleId(), detail, subject);
        }

        MutableStep(String id, String title, Instant at, Decision deferral, String detail) {
            this(id, title, at, outcome(deferral), deferral.ruleId(), detail, true);
        }

        private MutableStep(String id, String title, Instant at, String outcome, String ruleId, String detail,
                            boolean subject) {
            this.id = id;
            this.title = title;
            this.at = at;
            this.outcome = outcome;
            this.ruleId = ruleId;
            this.detail = detail;
            this.subject = subject;
        }

        Journey.Step build() {
            return new Journey.Step(id, Journey.Kind.CALL, at, title, withheld ? outcome + ", output withheld" : outcome,
                    ruleId, detail, labels, subject);
        }
    }
}
