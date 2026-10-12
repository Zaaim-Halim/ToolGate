package io.toolgate.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.toolgate.api.ArgValue;
import io.toolgate.api.Decision;
import io.toolgate.api.Label;
import io.toolgate.api.ObjectValue;
import io.toolgate.api.Principal;
import io.toolgate.api.Tenant;
import io.toolgate.api.ToolId;
import io.toolgate.spi.SessionEvent;
import io.toolgate.spi.SessionState;
import io.toolgate.spi.TaintSource;
import io.toolgate.spi.ValueCodec;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcSessionStoreTest {

    private static final Instant T0 = Instant.parse("2026-10-09T08:00:00.123456Z");
    private static final Principal MARIA = Principal.user("maria", Tenant.of("acme"), "support");

    private final DataSource database = newDatabase();
    private final JdbcSessionStore store = new JdbcSessionStore(database, ValueCodec.unencrypted(), Clock.fixed(T0, ZoneOffset.UTC));

    @Test
    void unknownSessionIsEmpty() {
        assertThat(store.state("s1")).isEqualTo(SessionState.empty("s1"));
        assertThat(store.events("s1")).isEmpty();
    }

    @Test
    void foldsEventsIntoTheSnapshotWithTaint() {
        store.append(decided("s1/1", new Decision.Allow("allow-list")), 0);
        store.append(result("s1/1", "readInvoice", Label.PRIVATE_DATA), 1);
        store.append(decided("s1/2", new Decision.Deny("allow-list", "not listed")), 2);

        assertThat(store.state("s1")).isEqualTo(new SessionState("s1", 3, 1, 1,
                Map.of(Label.PRIVATE_DATA, new TaintSource("s1/1", ToolId.local("readInvoice"))), Map.of()));
    }

    @Test
    void keepsTotalsInTheSnapshot() {
        store.append(new SessionEvent.CallReleased("s1", "s1:c1", "c1",
                Map.of("issueRefund.amount", new BigDecimal("199.99"), "transfer.quantity", BigDecimal.TEN), T0));

        assertThat(store.state("s1").total("issueRefund.amount")).isEqualByComparingTo("199.99");
        assertThat(store.state("s1").total("transfer.quantity")).isEqualByComparingTo("10");
    }

    @Test
    void conditionalAppendLosesToAnotherWriter() {
        store.append(decided("s1/1", new Decision.Allow("allow-list")), 0);

        assertThat(store.append(decided("s1/2", new Decision.Allow("allow-list")), 0)).isFalse();
        assertThat(store.state("s1").toolCalls()).isEqualTo(1);
        assertThat(store.events("s1")).hasSize(1);
    }

    @Test
    void unconditionalAppendLandsOnWhateverIsThere() {
        store.append(decided("s1/1", new Decision.Allow("allow-list")), 0);

        store.append(result("s1/1", "fetch", Label.UNTRUSTED_CONTENT));

        assertThat(store.state("s1").holds(Label.UNTRUSTED_CONTENT)).isTrue();
        assertThat(store.state("s1").version()).isEqualTo(2);
    }

    /** The log is what replay will read, so every kind of event must come back exactly. */
    @Test
    void keepsUnresolvedCallsInTheSnapshot() {
        store.append(new SessionEvent.CallUnresolved("s1", "s1:c1", ToolId.local("issueRefund"), T0));
        store.append(new SessionEvent.CallUnresolved("s1", "s1:c2", ToolId.mcp("payments", "refund"), T0));
        store.append(new SessionEvent.CallResolved("s1", "s1:c1", T0));

        assertThat(store.state("s1").unresolved()).containsExactly(Map.entry("s1:c2", ToolId.mcp("payments", "refund")));
        assertThat(store.state("s1").unresolvedCallOf(ToolId.mcp("payments", "refund"))).contains("s1:c2");
        assertThat(store.state("s1").unresolvedCallOf(ToolId.local("issueRefund"))).isEmpty();
    }

    @Test
    void eventLogReadsBackEveryKindOfEvent() {
        ObjectValue args = ObjectValue.of(Map.of("to", ArgValue.text("billing@mycompany.com"),
                "amount", ArgValue.number(new BigDecimal("480.5")),
                "lines", ArgValue.list(List.of(ArgValue.nullValue(), ArgValue.bool(false)))));
        List<SessionEvent> events = List.of(
                new SessionEvent.CallDecided("s1", "s1/1", "agent", MARIA, ToolId.mcp("mail", "send"), args,
                        new Decision.Downgrade("taint.trifecta", ToolId.local("draftEmail"), args),
                        new Decision.Deny("taint.trifecta", "trifecta"), Map.of(), "sha256:abc", T0),
                new SessionEvent.CallDecided("s1", "s1/2", "agent", Principal.service("job", Tenant.none()),
                        ToolId.local("issueRefund"), ObjectValue.empty(),
                        new Decision.Defer("issueRefund.amount", "480 is above 200", "s1:s1/2"),
                        new Decision.Terminate("budget.toolCalls", "used up"), Map.of(), "sha256:abc", T0.plusSeconds(1)),
                new SessionEvent.ResultRecorded("s1", "s1/1", ToolId.local("draftEmail"), false, true, Set.of(),
                        T0.plusSeconds(2)),
                new SessionEvent.ResultRecorded("s1", "s1/3", ToolId.local("fetch"), true, false,
                        Set.of(Label.UNTRUSTED_CONTENT, Label.PRIVATE_DATA), T0.plusSeconds(3)),
                new SessionEvent.Declassified("s1", Label.UNTRUSTED_CONTENT, "parsed into a schema", T0.plusSeconds(4)),
                new SessionEvent.CallReleased("s1", "s1:s1/2", "s1/2", Map.of("issueRefund.amount", new BigDecimal("480.5")),
                        T0.plusSeconds(5)),
                new SessionEvent.CallUnresolved("s1", "s1:s1/2", ToolId.local("issueRefund"), T0.plusSeconds(6)),
                new SessionEvent.CallResolved("s1", "s1:s1/2", T0.plusSeconds(7)));
        for (SessionEvent event : events) {
            store.append(event);
        }

        assertThat(store.events("s1")).containsExactlyElementsOf(events);
    }

    @Test
    void purgeIdleDeletesOnlyOldSessionsAndTheirEvents() {
        new JdbcSessionStore(database, ValueCodec.unencrypted(), Clock.fixed(T0.minus(Duration.ofDays(40)), ZoneOffset.UTC))
                .append(decided("old/1", new Decision.Allow("allow-list"), "old"));
        store.append(decided("s1/1", new Decision.Allow("allow-list")));

        assertThat(store.purgeIdle(Duration.ofDays(30))).isEqualTo(1);
        assertThat(store.state("old")).isEqualTo(SessionState.empty("old"));
        assertThat(store.events("old")).isEmpty();
        assertThat(store.state("s1").version()).isEqualTo(1);
    }

    /** A session that began before the window comes back whole; one with only a declassify does not. */
    @Test
    void readsWholeSessionsWithCallsInAWindow() {
        var monday = new JdbcSessionStore(database, ValueCodec.unencrypted(), Clock.fixed(T0.minus(Duration.ofDays(3)), ZoneOffset.UTC));
        var thursday = new JdbcSessionStore(database, ValueCodec.unencrypted(), Clock.fixed(T0, ZoneOffset.UTC));
        monday.append(decided("long/1", new Decision.Allow("allow-list"), "long"));
        thursday.append(decided("long/2", new Decision.Allow("allow-list"), "long"));
        monday.append(decided("old/1", new Decision.Allow("allow-list"), "old"));
        thursday.append(new SessionEvent.Declassified("quiet", Label.UNTRUSTED_CONTENT, "parsed", T0));
        thursday.append(decided("new/1", new Decision.Allow("allow-list"), "new"));

        try (var events = store.events(T0.minus(Duration.ofDays(1)), T0.plusSeconds(1))) {
            assertThat(events.map(event -> event.sessionId() + ":" + event.getClass().getSimpleName()))
                    .containsExactly("long:CallDecided", "long:CallDecided", "new:CallDecided");
        }
        try (var none = store.events(T0.plusSeconds(1), T0.plusSeconds(2))) {
            assertThat(none).isEmpty();
        }
        assertThatIllegalArgumentException().isThrownBy(() -> store.events(T0, T0));
    }

    private static SessionEvent decided(String callId, Decision applied) {
        return decided(callId, applied, "s1");
    }

    private static SessionEvent decided(String callId, Decision applied, String sessionId) {
        return new SessionEvent.CallDecided(sessionId, callId, "agent", MARIA, ToolId.local("t"), ObjectValue.empty(),
                applied, applied, Map.of(), "sha256:abc", T0);
    }

    private static SessionEvent result(String callId, String tool, Label label) {
        return new SessionEvent.ResultRecorded("s1", callId, ToolId.local(tool), true, false, Set.of(label), T0);
    }

    /**
     * A fresh database with ToolGate's tables. H2 here; the PostgreSQL and MySQL suites override it.
     *
     * @return the database
     */
    DataSource newDatabase() {
        return H2.withQueueTable();
    }
}
