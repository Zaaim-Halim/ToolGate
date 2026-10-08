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

## Modules

| Module          | Contents                                                                 |
| --------------- | ------------------------------------------------------------------------ |
| `toolgate-core` | Policy model, decision engine, validators, session state, audit events. No runtime dependencies. |

## License

Apache License 2.0. See [LICENSE](LICENSE).
