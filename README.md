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
| `toolgate-fuzz` | Jazzer fuzz targets for the parsers and validators. Test-only, never published. |
| `toolgate-benchmarks` | JMH benchmarks for `check`. Never published. |

## License

Apache License 2.0. See [LICENSE](LICENSE).
