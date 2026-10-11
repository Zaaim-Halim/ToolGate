# Audit, metrics and traces

Every decision ToolGate makes can be recorded in four places. The log is on by default; the rest
are off until you add them.

| Where | What you get | How |
| --- | --- | --- |
| Log (default) | One JSON line per event on the `toolgate.audit` logger | Nothing to do |
| Your own sink | Every event, for a SIEM, a database table or a queue | An `AuditSink` |
| Micrometer | Counter `toolgate.decisions`, timer `toolgate.check.duration` | `MicrometerAuditSink`, `MicrometerCheckTimer` |
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
set `toolgate.audit.log.enabled=false` to turn the log off.

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

## Your own observers

A `DecisionObserver` sees each check as it ends, on the thread that asked for it, with the decision
and how long the check took. It runs on the request path, so keep it fast and never block in it. A
failing observer never changes a decision; it is counted in `droppedAuditEvents()`. On Spring AI,
declare it as a `DecisionObserver` bean.
