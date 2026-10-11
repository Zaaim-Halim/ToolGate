# Replaying a policy change

Before deploying a stricter policy, run last week's recorded tool calls through it and see which
decisions would change.

## Record

Use the JDBC session store, which keeps an event log of every call:

```java
ToolGate.builder(policy).sessionStore(new JdbcSessionStore(dataSource))
```

Create its tables with the DDL in `toolgate-jdbc` (`JdbcApprovalQueue.Dialect.POSTGRESQL.ddl()`, or
the H2 and MySQL variants). The log holds the calls' real arguments: give the store a `ValueCodec` to
encrypt them, and purge idle sessions with `purgeIdle`.

## Replay

Ship the proposed policy as a class that supplies it:

```java
public final class ProposedPolicy implements Supplier<ToolGatePolicy> {
    public ToolGatePolicy get() { ... }
}
```

Then run the `toolgate` command, published as `toolgate-cli-<version>-all.jar`:

```sh
java -jar toolgate-cli-0.1.0-all.jar replay \
    --policy-class com.acme.ProposedPolicy \
    --catalog-class com.acme.ToolDefinitions \
    --classpath policy.jar,postgresql.jar \
    --jdbc-url jdbc:postgresql://db/app --jdbc-user toolgate \
    --since 7d
```

```text
Replayed 29 tool calls in 7 sessions (agents: support-chat)
Decisions that would change: 15
       15  local/sendEmail  allow -> deny  rule sendEmail.to
Unchanged: 14
Not replayed: result rules, because tool outputs are not stored
```

- `--catalog-class` supplies the tool definitions (`Supplier<ToolCatalog>`); needed when the policy
  has argument rules.
- `--owners-class` supplies an `OwnershipResolver`, for ownership rules.
- `--codec-class` supplies the `ValueCodec` the store was given, to open an encrypted event log.
- The password may come from `TOOLGATE_JDBC_PASSWORD` instead of `--jdbc-password`.
- `--fail-on-change` exits with 1 if anything would change, for a CI check.

`toolgate validate --policy-class ...` builds a policy and prints its hash, or why it is invalid.

From Java, the same is `PolicyReplay.builder(proposed).toolCatalog(catalog).build().replay(events)`.
