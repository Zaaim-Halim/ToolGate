# Contributing to ToolGate

Thank you for helping. Bug reports, framework compatibility reports and pull requests are welcome.
For a security problem, follow [SECURITY.md](SECURITY.md) instead of opening an issue.

## Building

Requires JDK 21 or later; the Maven wrapper brings Maven.

```sh
./mvnw verify                                          # every module's tests
./mvnw -pl toolgate-approvals-web -am -Pe2e verify     # the console in a real browser
./mvnw -Prelease -Dgpg.skip -DskipTests verify         # sources, Javadoc and SBOMs, as for a release
```

The compiler runs with `-Xlint:all -Werror`, so a warning fails the build. CI runs all three on every
pull request.

## Before you open a pull request

- **Open an issue first** for anything larger than a fix, so we can agree on the design.
- **Tests.** Every change comes with tests. Anything that parses model input also gets a property test
  or a fuzz target in `toolgate-fuzz`, and every new security rule gets a bypass case.
- **Monitor mode.** A new rule must log, never block, in monitor mode.
- **Audit.** A new decision path must produce an audit event with its rule ID and reason.
- **No new runtime dependency in `toolgate-core`.**
- **Public API.** Only `io.toolgate.api`, `io.toolgate.api.policy`, `io.toolgate.spi` and
  `io.toolgate.telemetry` are exported from core. Public types need Javadoc.
- **Docs.** Update the [guide](guide/README.md) when behavior changes, with a runnable example.

## Style

Match the surrounding code: Java 21 (records, sealed types, pattern matching), immutable values,
fail-closed defaults. Commit messages start with the module, such as `core: ...` or
`spring-ai, langchain4j: ...`, and say what changed for users.

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
