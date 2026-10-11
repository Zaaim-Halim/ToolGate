package io.toolgate.engine;

import io.toolgate.api.Decision;
import io.toolgate.api.Label;
import io.toolgate.api.Mode;
import io.toolgate.api.ReplayReport;
import io.toolgate.api.ToolCall;
import io.toolgate.api.ToolGate;
import io.toolgate.api.policy.BuiltInRules;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.rules.Spending;
import io.toolgate.spi.ApprovalQueue;
import io.toolgate.spi.HostResolver;
import io.toolgate.spi.OwnershipResolver;
import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.SessionEvent.CallDecided;
import io.toolgate.spi.SessionEvent.CallReleased;
import io.toolgate.spi.SessionEvent.Declassified;
import io.toolgate.spi.SessionEvent.ResultRecorded;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.SessionStore;
import io.toolgate.spi.ToolCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Runs recorded sessions through a proposed policy with a gate of its own; see {@code PolicyReplay}. */
public final class Replayer {

    private static final Set<Label> SOURCE_LABELS = EnumSet.of(Label.PRIVATE_DATA, Label.UNTRUSTED_CONTENT);

    private final ToolGatePolicy policy;
    private final OwnershipResolver owners;
    private final HostResolver hosts;
    private final ToolCatalog catalog;

    /**
     * @param owners  may be null when the policy has no ownership rules
     * @param hosts   may be null to check URLs without resolving names
     * @param catalog may be null when the policy pins no tools
     */
    public Replayer(ToolGatePolicy policy, OwnershipResolver owners, HostResolver hosts, ToolCatalog catalog) {
        this.policy = policy;
        this.owners = owners;
        this.hosts = hosts;
        this.catalog = catalog;
    }

    public ReplayReport replay(Stream<SessionEvent> history) {
        var sessions = new LinkedHashMap<String, List<SessionEvent>>();
        history.forEach(event -> sessions.computeIfAbsent(event.sessionId(), id -> new ArrayList<>()).add(event));
        var store = new RecordingStore(SessionStore.inMemory());
        var clock = new ReplayClock();
        ToolGate.Builder builder = ToolGate.builder(policy)
                .sessionStore(store)
                .approvalQueue(ApprovalQueue.inMemory())
                // Replayed calls are not decisions; they must never reach the real audit log.
                .noDefaultLog()
                .clock(clock);
        if (owners != null) {
            builder.ownershipResolver(owners);
        }
        if (hosts != null) {
            builder.hostResolver(hosts);
        }
        if (catalog != null) {
            builder.toolCatalog(catalog);
        }
        var changes = new ArrayList<ReplayReport.Change>();
        var agents = new TreeSet<String>();
        int[] counts = new int[2];
        try (ToolGate gate = builder.build()) {
            sessions.values().forEach(events -> new SessionReplay(gate, store, clock, changes, agents, counts).run(events));
        }
        return new ReplayReport(sessions.size(), counts[0], agents, changes, counts[1]);
    }

    /** One session: history's events in order, with the replay's own decisions and state. */
    private final class SessionReplay {

        private final ToolGate gate;
        private final RecordingStore store;
        private final ReplayClock clock;
        private final List<ReplayReport.Change> changes;
        private final Set<String> agents;
        private final int[] counts;
        private final Map<String, ToolCall> running = new HashMap<>();
        private final Map<String, ToolCall> deferred = new HashMap<>();
        private final Set<String> recordedResults = new HashSet<>();
        private final Map<String, Boolean> withheldInHistory = new HashMap<>();
        private boolean diverged;

        SessionReplay(ToolGate gate, RecordingStore store, ReplayClock clock, List<ReplayReport.Change> changes,
                      Set<String> agents, int[] counts) {
            this.gate = gate;
            this.store = store;
            this.clock = clock;
            this.changes = changes;
            this.agents = agents;
            this.counts = counts;
        }

        void run(List<SessionEvent> events) {
            events.forEach(event -> {
                if (event instanceof ResultRecorded result) {
                    recordedResults.add(result.callId());
                    withheldInHistory.put(result.callId(), result.withheld());
                }
            });
            for (SessionEvent event : events) {
                switch (event) {
                    case CallDecided decided -> decide(decided);
                    case CallReleased released -> release(released);
                    case ResultRecorded result -> result(result.callId(), result.succeeded(), result.at());
                    case Declassified declassified -> store.append(declassified);
                }
            }
        }

        private void decide(CallDecided recorded) {
            counts[0]++;
            agents.add(recorded.agentId());
            clock.set(recorded.at());
            var call = new ToolCall(recorded.callId(), recorded.principal(), recorded.agentId(), recorded.sessionId(),
                    recorded.tool(), recorded.args(), recorded.at());
            // A call the adapter refused was never read, so its recorded arguments are not what the model
            // sent; replaying them as a check would invent a call. It is refused again, as recorded.
            Decision applied = recorded.computed() instanceof Decision.Deny(String ruleId, String reason)
                    && BuiltInRules.ADAPTER_REFUSALS.contains(ruleId)
                    ? gate.reject(call, ruleId, reason)
                    : gate.check(call);
            Decision computed = store.computed(recorded.sessionId(), recorded.callId()).orElse(applied);
            if (!same(recorded.computed(), computed)) {
                changes.add(new ReplayReport.Change(recorded.sessionId(), recorded.callId(), recorded.agentId(),
                        recorded.tool(), recorded.computed(), computed, diverged));
                diverged = true;
            }
            switch (applied) {
                case Decision.Allow allow -> runs(call);
                case Decision.Downgrade downgrade -> runs(call.withTool(downgrade.safeTool(), downgrade.safeArgs()));
                case Decision.Defer defer -> deferred.put(call.callId(), call);
                case Decision.Deny deny -> { }
                case Decision.Terminate terminate -> { }
            }
        }

        private void runs(ToolCall ran) {
            running.put(ran.callId(), ran);
            if (!recordedResults.contains(ran.callId())) {
                // It never ran in history, so nothing was recorded: take it to succeed.
                counts[1]++;
                result(ran.callId(), true, ran.at());
            }
        }

        private void release(CallReleased released) {
            ToolCall call = deferred.remove(released.callId());
            if (call == null) {
                return;
            }
            clock.set(released.at());
            store.append(new CallReleased(released.sessionId(), released.approvalId(), released.callId(),
                    Spending.of(policy, call), released.at()));
            runs(call);
        }

        private void result(String callId, boolean succeeded, Instant at) {
            ToolCall ran = running.remove(callId);
            if (ran == null) {
                return;
            }
            boolean withheld = withheldInHistory.getOrDefault(callId, false) && policy.mode() == Mode.ENFORCE;
            var labels = EnumSet.noneOf(Label.class);
            if (!withheld) {
                labels.addAll(policy.tool(ran.tool()).labels());
                labels.retainAll(SOURCE_LABELS);
            }
            store.append(new ResultRecorded(ran.sessionId(), callId, ran.tool(), succeeded, withheld, labels, at));
        }
    }

    /** Same kind, same rule and, for a downgrade, the same safe tool; reasons and approval IDs may differ. */
    static boolean same(Decision before, Decision after) {
        if (before.getClass() != after.getClass() || !before.ruleId().equals(after.ruleId())) {
            return false;
        }
        return !(before instanceof Decision.Downgrade downgrade)
                || downgrade.safeTool().equals(((Decision.Downgrade) after).safeTool());
    }

    /** The in-memory store, noting what the gate computed for each call as it appends the decision. */
    private static final class RecordingStore implements SessionStore {

        private final SessionStore delegate;
        private final Map<String, Decision> computed = new ConcurrentHashMap<>();

        RecordingStore(SessionStore delegate) {
            this.delegate = delegate;
        }

        Optional<Decision> computed(String sessionId, String callId) {
            return Optional.ofNullable(computed.remove(sessionId + "\u0000" + callId));
        }

        @Override
        public SessionState state(String sessionId) {
            return delegate.state(sessionId);
        }

        @Override
        public boolean append(SessionEvent event, long expectedVersion) {
            boolean appended = delegate.append(event, expectedVersion);
            if (appended) {
                note(event);
            }
            return appended;
        }

        @Override
        public void append(SessionEvent event) {
            delegate.append(event);
            note(event);
        }

        private void note(SessionEvent event) {
            if (event instanceof CallDecided decided) {
                computed.put(decided.sessionId() + "\u0000" + decided.callId(), decided.computed());
            }
        }
    }

    /** A clock set to each recorded event's time, so expiry and audit times follow history. */
    private static final class ReplayClock extends Clock {

        private volatile Instant now = Instant.EPOCH;

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
