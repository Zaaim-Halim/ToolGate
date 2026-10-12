# Using the core directly

The Spring AI and LangChain4j adapters do everything on this page for you. Use `toolgate-core` on its
own to gate another framework, or your own agent loop. It has no runtime dependencies.

```xml
<dependency>
    <groupId>io.github.zaaim-halim.toolgate</groupId>
    <artifactId>toolgate-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

Only the packages `io.toolgate.api`, `io.toolgate.api.policy`, `io.toolgate.spi` and
`io.toolgate.telemetry` are exported. Everything else is internal and may change in any release.

## 1. Build a gate

```java
ToolGatePolicy policy = ToolGatePolicy.builder()
        .mode(Mode.ENFORCE)
        .agent("research-bot", a -> a
                .principal(Principal.service("research-bot", Tenant.of("acme")))
                .allow("fetchPage")
                .rule("fetchPage.url", schemes("https"), hosts("*.wikipedia.org")))
        .build();

ToolCatalog.Mutable catalog = ToolCatalog.inMemory();
ToolGate gate = ToolGate.builder(policy)
        .toolCatalog(catalog)
        .build();
```

A gate is thread-safe. Build one per application and `close()` it when the application stops, so
queued audit events are written.

A tool catalog is required when the policy has argument rules, bound arguments or pins: a call may
pass only the arguments its tool's definition declares. Put each tool's definition in it as the model is offered it
(`catalog.put(definition)`), or pass the definition with each check, as below.

The builder also takes a `sessionStore` and an `approvalQueue` (in memory by default; JDBC with
`toolgate-jdbc`), an `ownershipResolver` for ownership rules, audit sinks, observers and approval
listeners. See [Running in production](production.md).

## 2. Check each call before it runs

```java
ToolDefinition offered = ToolDefinition.of(ToolId.local("fetchPage"), "Fetch a web page",
        """
        {"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}""");

ToolCall call = new ToolCall(
        callId,                                   // unique within the session
        principal,                                // who the agent acts for
        "research-bot",                           // the agent, as named in the policy
        sessionId,                                // the conversation or job run
        ToolId.local("fetchPage"),                // or ToolId.mcp("jira", "search_issues")
        ArgValue.object(Map.of("url", ArgValue.text(url))),
        Instant.now());

Decision decision = gate.check(call, offered);
```

Pass the arguments exactly as the tool will receive them. `ArgValueJson.read(json)` turns the model's
JSON arguments into an `ArgValue`, strictly: invalid JSON, duplicate keys and very deep nesting are
refused, so a model cannot pass arguments that two parsers read differently.

## 3. Apply the decision

`Decision` is sealed, so a `switch` over it must handle every outcome:

```java
String toModel = switch (decision) {
    case Decision.Allow allow -> run(call);
    case Decision.Deny deny -> "This tool call is not allowed.";
    case Decision.Defer defer -> "Waiting for approval " + defer.approvalId() + ".";
    case Decision.Downgrade downgrade -> run(call.withTool(downgrade.safeTool(), downgrade.safeArgs()));
    case Decision.Terminate terminate -> throw new AgentStoppedException(terminate.reason());
};
```

- **Deny**: do not run the tool. Tell the model it was refused, without the reason, which can quote
  the denied value.
- **Defer**: do not run the tool now. The call waits in the approval queue.
- **Downgrade**: run the safe tool, such as a draft instead of a send, with the arguments given.
- **Terminate**: end the whole agent run, for example when a budget is used up.

In monitor mode `check` returns `Allow` for every call; the audit log records what enforcement would
have done.

If your adapter must refuse a call before it can check it, for example because its arguments are
ambiguous, record that with `gate.reject(call, ruleId, reason)`, so it is audited and counts toward the
denial budget.

## 4. Record what the tool returned

Taint labels, result rules and bound arguments need the result of every call that ran, failed ones
included. Pass the output as structured values, such as `ArgValueJson.read(json)`, so trusted fields can
be read from it:

```java
ResultDecision result = gate.recordResult(call, new ToolResult.Succeeded(output));
// or new ToolResult.Failed(errorMessageTheModelSaw)

if (result instanceof ResultDecision.Withhold) {
    toModel = "The result was withheld by policy.";
}
```

Show the model the output only on `Release`.

## 5. Run approved calls

A reviewer approves a deferred call with `gate.approve(approvalId, reviewer, currentPrincipal, comment,
digest)`, typically from a review screen built on `pending(viewer)` and `reviewItem(approvalId, viewer)`
(see [Approvals](policies.md#approvals)). The call is re-checked, then waits as `RELEASED` for an
executor. On a schedule, such as every 30 seconds:

```java
gate.sweep();                                                  // expires old calls, warns of expiring ones
for (PendingCall released : gate.withStatus(ApprovalStatus.RELEASED)) {
    gate.claim(released.approvalId(), executorId, Duration.ofMinutes(5)).ifPresent(claimed -> {
        ToolResult result = runTool(claimed.call(), claimed.idempotencyKey());
        gate.finished(released.approvalId(), executorId, result);
    });
}
```

`claim` succeeds on one instance only, so in a cluster each call runs once. If an executor dies before
`finished`, the next `sweep()` marks the call `UNKNOWN`, and new calls to that tool in the session are
denied until someone checks the real system and calls `resolve(approvalId, by, ran, reason)`.
