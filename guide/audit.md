# Audit, metrics and traces

Every decision ToolGate makes can be recorded in five places. The log is on by default; the rest
are off until you add them.

| Where | What you get | How |
| --- | --- | --- |
| Log (default) | One JSON line per event on the `toolgate.audit` logger | Nothing to do |
| Your own sink | Every event, for a SIEM, a database table or a queue | An `AuditSink` |
| Your database | Every event as a row, read views for dashboards, approval figures | `JdbcAuditSink`, `ToolGateQueries` |
| Micrometer | Decision counter, check timer, approval gauges | `MicrometerAuditSink`, `MicrometerCheckTimer`, `MicrometerApprovalMetrics` |
| OpenTelemetry | Decision attributes on the current span, an event for each refusal | `OpenTelemetryDecisions` |

## The log

```json
{"event":"toolgate.decision","agent":"support-chat","tool":"local/getInvoice","args":{"id":"text(7)"},"decision":"deny","rule":"getInvoice.id","computed":"deny","computedRule":"getInvoice.id","mode":"enforce","budget":{"toolCalls":2,"limit":40},...}
```

Spring Boot routes the logger to its own logging, so the lines go wherever your logs already go;
filter on the logger name `toolgate.audit`. Argument values are never logged, only their type and
size, or keyed hashes if you set `argumentHashKey`. Deny reasons are logged in full and can quote the
value that was denied.

## Your own sinks

Each sink gets every event, on one background thread, never on the path of a tool call. Adding a sink
keeps the log; a failing sink keeps nothing from the others.

```java
ToolGate gate = ToolGate.builder(policy)
        .auditSink(mySiemSink)
        .auditSink(myDatabaseSink)
        .build();
```

Call `noDefaultLog()` to turn the log off. On Spring AI, declare each sink as an `AuditSink` bean, and
set `toolgate.audit.log.enabled=false` to turn the log off. `event.toJson()` gives a sink the same JSON
line the log writes.

## In your database

With `toolgate-jdbc`, the audit trail and the approval queue live in your database, where your own
screens, reports and dashboards can read them. The DDL for H2, PostgreSQL and MySQL
(`JdbcApprovalQueue.Dialect.POSTGRESQL.ddl()`) creates every ToolGate table and the read views.

```java
ToolGate gate = ToolGate.builder(policy)
        .approvalQueue(new JdbcApprovalQueue(dataSource, codec))
        .sessionStore(new JdbcSessionStore(dataSource, codec))
        .auditSink(new JdbcAuditSink(dataSource))
        .build();
```

On Spring AI, declare the three as beans. `JdbcAuditSink` writes one row per event into
`toolgate_audit`: the event's JSON line, as the log has it, plus columns to query by (kind, session,
approval, agent, actor, tenant, tool, outcome, rule, mode). Like any sink it runs off the request path;
a database outage drops rows, counted in `toolgate.audit.dropped`, and never slows a decision.

**Read views.** For dashboards and SQL reports, read the `toolgate_v1_*` views rather than the tables.
Their columns stay the same across ToolGate versions (a change comes as `toolgate_v2_*`), and they
leave out everything sealed or able to quote an argument: payloads, outcomes, comments, reasons and
session details. Times are epoch microseconds, UTC.

| View | One row per |
| --- | --- |
| `toolgate_v1_approvals` | Deferred call: status, agent, principal, tool, rule, deferred, expires, executor, finished |
| `toolgate_v1_approval_decisions` | Decision on a deferred call: kind, who, access, when |
| `toolgate_v1_decisions` | Gate decision: call, session, agent, principal, tool, outcome, rule, mode, policy |
| `toolgate_v1_sessions` | Session: version, tool calls, denials, last update |

**From Java.** `ToolGateQueries` reads the same data:

```java
var queries = new ToolGateQueries(dataSource);
ApprovalStats week = queries.stats(Duration.ofDays(7));   // waiting, oldest, unknown, by status, p50/p95 time to decide
List<AuditRow> trail = queries.audit(AuditQuery.latest().approval(approvalId));
List<AuditRow> denied = queries.audit(AuditQuery.latest().kinds("DECIDED").outcome("deny").since(yesterday));
```

Neither the views nor `ToolGateQueries` filter by viewer or tenant: they are for operators. Show them
only to people who may see every tenant, or filter by `principal_tenant` yourself. Reviewers see their
calls through the console or `pending(viewer)`.

**Retention.** Rows are kept until you delete them. `JdbcAuditSink.purgeBefore(cutoff)` deletes old
audit rows, `JdbcApprovalQueue.purgeBefore(cutoff)` deletes calls that are over (executed, failed,
refused, rejected, expired, cancelled; never one waiting, approved, running or with an unknown
outcome), and `JdbcSessionStore.purgeIdle(idle)` deletes idle sessions. On Spring AI, one setting
purges them all daily:

```properties
toolgate.jdbc.retention=400d
toolgate.jdbc.session-retention=30d   # optional: session events hold real argument values
```

400 days covers a yearly audit with a margin; regulated teams often keep longer.

## Micrometer

Add `io.micrometer:micrometer-core` to your application; ToolGate does not bring it.

On Spring AI, with a `MeterRegistry` bean (`spring-boot-starter-actuator` gives you one):

```properties
toolgate.audit.micrometer.enabled=true
```

Without Spring:

```java
ToolGate gate = ToolGate.builder(policy)
        .auditSink(new MicrometerAuditSink(registry, policy))
        .decisionObserver(new MicrometerCheckTimer(registry))
        .build();
```

- `toolgate.decisions` (counter): tags `outcome` and `rule` (what was applied), `computed` and
  `computedRule` (what enforcement does; in monitor mode, `outcome` is always `allow`), `agent` and
  `tool`. A tool the agent's policy does not list is tagged `unlisted`, since a model can call tools
  by any name and each name would become its own time series.
- `toolgate.check.duration` (timer): tags `outcome` and `agent`. It includes the session store, so a
  slow database shows here.
- `toolgate.audit.dropped` (counter, Spring AI only): audit events a sink missed and observer calls
  that failed. Alert on any increase: enforcement is unaffected, but the audit trail has gaps. Without
  Spring, register `ToolGate::droppedAuditEvents` as a `FunctionCounter` yourself.

**Approvals.** `MicrometerApprovalMetrics` shows whether reviewers keep up. On Spring AI it comes with
`toolgate.audit.micrometer.enabled=true`; without Spring, give it the gate's queue and add it as a sink:

```java
var metrics = new MicrometerApprovalMetrics(registry, queue, Duration.ofSeconds(30));
ToolGate gate = ToolGate.builder(policy).approvalQueue(queue).auditSink(metrics).build();
```

- `toolgate.approvals.pending` (gauge, tag `agent`): calls waiting for a decision.
- `toolgate.approvals.oldest` (gauge, seconds): how long the oldest one has waited. Alert when it nears
  your expiry.
- `toolgate.approvals.unknown` (gauge): approved calls whose run has an unknown outcome. Alert on any:
  someone must check the real system and resolve them.
- `toolgate.approvals.decided` (counter, tags `status` and `agent`): what became of deferred calls.
- `toolgate.approvals.latency` (timer, tag `status`): from deferral to release or rejection.

The gauges read the queue every 30 seconds (`toolgate.approvals.metrics.refresh`) on a background
thread, so a scrape never waits on the database.

## OpenTelemetry

Add `io.opentelemetry:opentelemetry-api` and a tracing setup that makes spans current, such as
Spring Boot's with `micrometer-tracing-bridge-otel`. ToolGate uses only the API.

On Spring AI:

```properties
toolgate.audit.opentelemetry.enabled=true
```

Without Spring: `ToolGate.builder(policy).decisionObserver(new OpenTelemetryDecisions())`.

Each check sets attributes on the current span: `toolgate.decision`, `toolgate.rule`, `toolgate.agent`,
`toolgate.tool`, `toolgate.session` and `toolgate.mode`, plus `toolgate.computed.decision` and
`toolgate.computed.rule` when monitor mode applied something else. ToolGate checks a call inside the
adapter's tool callback, so with tracing set up the current span is usually the framework's tool-call
span; without one, attributes land on whatever span is current, and a later check under the same span
overwrites them. A call the policy refuses also adds the event `toolgate.denied`, with the rule
and `toolgate.enforced` (false in monitor mode). Deny reasons are left out of traces, since they can
quote the denied value; the log has them.

## Approval events

An `ApprovalListener` hears what happens to deferred calls: `deferred`, `decided` (approved, refused,
rejected, cancelled, expired), `recorded` (a super admin's approval waiting for a second person),
`finished` (executed, failed or unknown) and `superAdminBurst`. Use it to tell reviewers a call is
waiting, or to page someone. It is an audit sink underneath, so it runs off the request path, and its
events carry no argument values: link to your review screen instead of describing the call.

```java
ToolGate.builder(policy).approvalListener(new SlackApprovals(webhook)).build();
```

On Spring AI, declare it as an `ApprovalListener` bean. To tell the agent what became of the calls it
asked for, set `toolgate.approvals.outcome.chat-memory=true` (with a `ChatMemory` bean): ToolGate adds
a message it writes itself, never the tool's output, to the conversation, so the agent knows on the
user's next turn. It suits chat agents, whose session is the conversation ID.

## Your own observers

A `DecisionObserver` sees each check as it ends, on the thread that asked for it, with the decision
and how long the check took. It runs on the request path, so keep it fast and never block in it. A
failing observer never changes a decision; it is counted in `droppedAuditEvents()`. On Spring AI,
declare it as a `DecisionObserver` bean.
