# LangChain4j in monitor mode

For LangChain4j 1.22 AI services. About five minutes.

## 1. Add the adapter

```xml
<dependency>
    <groupId>io.github.zaaim-halim.toolgate</groupId>
    <artifactId>toolgate-langchain4j</artifactId>
    <version>0.1.0</version>
</dependency>
```

## 2. Build ToolGate with a policy in monitor mode

```java
import io.toolgate.api.Mode;
import io.toolgate.api.policy.ToolGatePolicy;
import io.toolgate.langchain4j.ToolGateLangChain4j;

ToolGateLangChain4j toolGate = ToolGateLangChain4j.builder(ToolGatePolicy.builder()
                .mode(Mode.MONITOR)
                .agent("support-chat", a -> a.allow("getOrderStatus", "searchDocs"))
                .build())
        .build();
```

## 3. Register tools through ToolGate, not on the builder

```java
Assistant assistant = toolGate.agent("support-chat")
        .tools(new OrderTools(), new DocsTools())
        .toolProvider(mcpToolProvider)          // optional: MCP tools are gated too
        .applyTo(AiServices.builder(Assistant.class).chatModel(model))
        .build();
```

ToolGate installs a `beforeToolExecution` hook. A tool registered around it, on the builder, ends the
run instead of running unchecked. If you have a hook of your own, pass it with `.hook(...)`: an AI
service keeps a single hook, and setting one on the builder afterwards replaces ToolGate's.

## 4. Pass who the call is for

Give the service method an `InvocationParameters` argument:

```java
interface Assistant {
    String chat(@UserMessage String question, InvocationParameters parameters);
}

assistant.chat(question, ToolGateParameters.of(Principal.user("maria", Tenant.of("acme")), "chat-42"));
```

Without a session, the `@MemoryId` is used, never LangChain4j's shared `"default"` memory. Without a
principal, the identity the policy declares for the agent (`.principal(...)`) is used; with neither,
the run ends.

## 5. Run and read the log

Decisions go to the `toolgate.audit` logger as JSON lines; `computed` and `computedRule` show what
enforcement would have done. Switch to `Mode.ENFORCE` when they look right. To send decisions to a
SIEM, Micrometer or OpenTelemetry as well, see [Audit, metrics and traces](audit.md).

## 6. Approvals

A deferred call waits in the gate's approval queue. Approve it with `toolGate.approve(approvalId,
reviewer, currentPrincipal, comment, digest)`, which re-checks it and runs it. To run calls approved on
another instance or from a console, call `toolGate.runApproved()` on a schedule, such as every 30
seconds; it also sweeps the queue, expiring old calls. Stores, audit sinks and listeners are set on the
underlying gate's builder: `ToolGateLangChain4j.builder(policy).gate(g -> g.approvalQueue(queue))`. Register tools that can need approval with
`agent(...).tools(...)`, so every instance has them. See [Approvals](policies.md#approvals).

## Limitations

Not available for tools registered through ToolGate: LangChain4j's compensating actions
(`@CompensateFor`) and tool search (`toolSearchStrategy`). MCP resources presented as tools are always
denied. See [Known limitations](limitations.md#langchain4j).

## Next

- Add [argument rules, taint labels and budgets](policies.md), and [test them](testing.md).
- Before enforcing in production, work through [Running in production](production.md).
