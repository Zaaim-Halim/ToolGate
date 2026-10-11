# Spring AI in monitor mode

For Spring AI 2.0 on Spring Boot 4. About five minutes.

## 1. Add the starter

```xml
<dependency>
    <groupId>io.github.zaaim-halim.toolgate</groupId>
    <artifactId>toolgate-spring-ai</artifactId>
    <version>0.1.0</version>
</dependency>
```

## 2. Declare a policy in monitor mode

List each agent and the tools it uses. In monitor mode nothing is blocked; every decision is logged
as if it were enforced.

```java
import io.toolgate.api.Mode;
import io.toolgate.api.policy.ToolGatePolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ToolGateConfig {

    @Bean
    ToolGatePolicy toolGatePolicy() {
        return ToolGatePolicy.builder()
                .mode(Mode.MONITOR)
                .agent("support-chat", a -> a.allow("getOrderStatus", "searchDocs"))
                .build();
    }
}
```

The application does not start without a `ToolGatePolicy` bean: without one, every tool would run
unchecked.

## 3. Name the agent

With one agent, set it once:

```properties
toolgate.agent=support-chat
```

With several, pass it per request: `.toolContext(ToolGateContext.of("support-chat", principal, sessionId))`.
The principal is the signed-in Spring Security user by default, or, for a job outside any web request,
the agent's service identity. The session is the chat memory's conversation ID; a request without chat
memory is a session of its own, since the model sees nothing from earlier requests.

## 4. Build chat clients from Spring Boot's builder

Inject `ChatClient.Builder`; its clients are gated. A client built by hand with
`ChatClient.create(model)` is not: use `ToolGateChatClients.builder(model)` instead. Tool beans are
guarded either way: a hand-built client that runs one is refused (or logged, in monitor mode).

## 5. Run and read the log

Every decision is a JSON line on the `toolgate.audit` logger:

```json
{"event":"toolgate.decision","agent":"support-chat","tool":"local/deleteOrder","decision":"allow","rule":"mode.monitor","computed":"deny","computedRule":"allow-list","mode":"monitor",...}
```

`decision` is what happened; `computed` and `computedRule` are what enforcement would have done. When the log shows only the decisions you want, switch
to `Mode.ENFORCE`. To send decisions to a SIEM, Micrometer or OpenTelemetry as well, see
[Audit, metrics and traces](audit.md).

## Next

- Add [argument rules, taint labels and budgets](policies.md).
- Keep sessions and the review queue in your database with `toolgate-jdbc` when you run more than one instance.
- Before deploying a stricter policy, [replay](replay.md) last week's traffic through it.
