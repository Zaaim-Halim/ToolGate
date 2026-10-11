# ToolGate

ToolGate is a Java library that decides, outside the model, whether each tool call an AI agent
makes is allowed, and records every decision with its reason. It targets Spring AI, LangChain4j
and Quarkus applications on Java 21 or later.

The model proposes; ToolGate decides.

> **Status:** early development. Nothing is published yet and every API may change.

Start with the [guide](guide/README.md): ToolGate in monitor mode on Spring AI or LangChain4j in a few minutes.

## What it does

- **Decides every tool call** against a policy written in Java: allow-lists, argument rules (URLs, paths,
  emails, SQL, numbers, ownership, roles), budgets, and taint rules that hold a send after the agent read
  untrusted content.
- **Applies the decision**: allow, deny, downgrade to a safe tool (a draft instead of a send), defer for a
  person to approve, or end the run. Monitor mode records what it would do without blocking anything.
- **Records every decision** as a JSON line on the `toolgate.audit` logger, and optionally to your own
  sinks, Micrometer and OpenTelemetry ([guide](guide/audit.md)).
- **Approvals people can use**: a deferred call is approved, rejected or withdrawn by reviewers in its own
  tenant, super admins (justified, audited, optionally needing a second person) or the user who asked;
  re-checked when approved; run once under a lease on any instance; and its outcome reaches the agent. A
  review console and REST API for Spring applications ship as an opt-in module
  ([policies](guide/policies.md#approvals), [console](guide/approvals-console.md)).

## Building

Requires JDK 21 or later. The Maven wrapper downloads the right Maven version.

```sh
./mvnw verify
```

The console's browser tests download a browser on first run, so they run only with `-Pe2e`:

```sh
./mvnw -pl toolgate-approvals-web -am -Pe2e verify
```

Every build also replays the saved fuzz inputs. To fuzz one target for real, for example for an hour:

```sh
JAZZER_FUZZ=1 ./mvnw -pl toolgate-fuzz -am test -Dtest=UrlCheckFuzzTest -Djazzer.max_duration=1h
```

To measure `check` with JMH:

```sh
./mvnw -pl toolgate-benchmarks -am package -DskipTests
java -jar toolgate-benchmarks/target/benchmarks.jar
```

## Modules

| Module          | Contents                                                                 |
| --------------- | ------------------------------------------------------------------------ |
| `toolgate-core` | Policy model, decision engine, validators, session state, approvals, audit events. No runtime dependencies; Micrometer and the OpenTelemetry API are optional, for its telemetry package. |
| `toolgate-jdbc` | Approval queue and session store in the application's database, through plain JDBC, for applications with more than one instance. Ships DDL for H2, PostgreSQL and MySQL. |
| `toolgate-email` | The `emailDomains` rule: recipients parsed with Jakarta Mail, every one checked, and every address form that mail libraries read differently denied. |
| `toolgate-sql` | The `readOnlySql` rule: a pre-check on JSqlParser that denies anything but one query. It only denies; the database enforces read-only. |
| `toolgate-safe-jdbc` | `SafeJdbc`, for tool authors: runs a model's query in a read-only transaction on PostgreSQL, MySQL and MariaDB (or as a read-only role elsewhere), with a timeout and limits on what comes back. |
| `toolgate-spring-ai` | Spring AI 2.0 adapter and Spring Boot auto-configuration: every local and MCP tool call is checked by ToolGate, with principals from Spring Security or the agent's service identity, and enforce, monitor or off modes. Runs approved calls on any instance, and hands chat screens their deferred calls. |
| `toolgate-approvals-web` | An opt-in review console and REST API for deferred calls, for Spring MVC and WebFlux applications with Spring Security. Off until `toolgate.approvals.ui.enabled=true`. |
| `toolgate-safe-http` | `SafeHttp`, for tool authors: an Apache HttpClient 5 client that checks every request and redirect against URL rules before connecting, and connects only to the addresses it checked. |
| `toolgate-cli` | The `toolgate` command: `validate` a policy, and `replay` recorded tool calls through a proposed one before deploying it. |
| `toolgate-langchain4j` | LangChain4j 1.22 adapter: tools registered through ToolGate for an agent are checked in a fail-closed `beforeToolExecution` hook, which ends the run on a Terminate or an unregistered tool; their executors apply the decision. Covers sync, async, streaming, concurrent and immediate-return tools and MCP tool providers. |
| `toolgate-test` | Test your policies: scripted sessions play tool calls as a compromised model would, and assert what ToolGate decides. A JUnit 5 extension (`@ToolGateTest`) or plain `ScriptedSession.of(...)`. For test scope. |
| `toolgate-demo` | Three agents on Spring AI behind one policy: Case A's invoice job, Case E's ticket triage and an interactive inbox assistant. Its tests run the design's cases and the trifecta measurement end to end against a scripted model. Never published. |
| `toolgate-fuzz` | Jazzer fuzz targets for the parsers and validators. Test-only, never published. |
| `toolgate-benchmarks` | JMH benchmarks for `check`. Never published. |

## Trifecta trigger rate

How often taint rules hold the demo agents' calls, measured with the default policy (October 11, 2026). Fourteen
session shapes run end to end through Spring AI on a scripted model, weighted by an assumed mix of 100 sessions per
agent. Each shape's outcome is a property of the policy; the rates depend on the assumed mix. A trigger is a call
`taint.trifecta` denies or defers; downgrades and `taint.irreversible` are counted apart.

| Agent | Sending sessions held or downgraded | Trifecta held | Trifecta downgraded | `taint.irreversible` held | `taint.irreversible` downgraded | Any taint rule |
| --- | --- | --- | --- | --- | --- | --- |
| `invoice-processor` (headless) | 40 of 100 | 0% | 40% | 0% | 0% | 40% |
| `ticket-triage` (headless) | 67 of 67 | 0% | 32% | 0% | 35% | 67% |
| `inbox-assistant` (interactive) | 47 of 62 | 22% | 0% | 25% | 0% | 47% |

Every session that reads untrusted content and then sends is held or downgraded. The headless agents keep working
because their sends become drafts; the interactive assistant asks its user. This is a known limit, to be addressed
together with bound arguments. The report per session shape is regenerated by:

```sh
./mvnw -pl toolgate-demo -am test -Dtest=TrifectaRateTest -Dsurefire.failIfNoSpecifiedTests=false
cat toolgate-demo/target/trifecta-rate.md
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
