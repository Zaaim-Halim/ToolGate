# ToolGate

ToolGate is a Java library that decides, outside the model, whether each tool call an AI agent
makes is allowed, and records every decision with its reason. It targets Spring AI, LangChain4j
and Quarkus applications on Java 21 or later.

The model proposes; ToolGate decides.

> **Status:** early development. Nothing is published yet and every API may change.

## Building

Requires JDK 21 or later. The Maven wrapper downloads the right Maven version.

```sh
./mvnw verify
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
| `toolgate-core` | Policy model, decision engine, validators, session state, audit events. No runtime dependencies. |
| `toolgate-jdbc` | Approval queue and session store in the application's database, through plain JDBC, for applications with more than one instance. Ships DDL for H2, PostgreSQL and MySQL. |
| `toolgate-email` | The `emailDomains` rule: recipients parsed with Jakarta Mail, every one checked, and every address form that mail libraries read differently denied. |
| `toolgate-sql` | The `readOnlySql` rule: a pre-check on JSqlParser that denies anything but one query. It only denies; the database enforces read-only. |
| `toolgate-safe-jdbc` | `SafeJdbc`, for tool authors: runs a model's query in a read-only transaction on PostgreSQL, MySQL and MariaDB (or as a read-only role elsewhere), with a timeout and limits on what comes back. |
| `toolgate-test` | Test your policies: scripted sessions play tool calls as a compromised model would, and assert what ToolGate decides. A JUnit 5 extension (`@ToolGateTest`) or plain `ScriptedSession.of(...)`. For test scope. |
| `toolgate-fuzz` | Jazzer fuzz targets for the parsers and validators. Test-only, never published. |
| `toolgate-benchmarks` | JMH benchmarks for `check`. Never published. |

## License

Apache License 2.0. See [LICENSE](LICENSE).
