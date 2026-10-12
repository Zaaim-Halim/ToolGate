# Drafting and replaying policies

The `toolgate` command works on recorded tool calls. `suggest` drafts a policy from them, such as after
a week in monitor mode; `replay` runs them through a proposed policy before you deploy it, and reports
which decisions would change.

## Record

Use the JDBC session store, which keeps an event log of every call:

```java
ToolGate.builder(policy).sessionStore(new JdbcSessionStore(dataSource))
```

Create its tables with the DDL in `toolgate-jdbc` (`JdbcApprovalQueue.Dialect.POSTGRESQL.ddl()`, or
the H2 and MySQL variants). The log holds the calls' real arguments: give the store a `ValueCodec` to
encrypt them, and purge idle sessions with `purgeIdle`.

## Suggest

```sh
java -jar toolgate-cli-0.1.0-all.jar suggest \
    --classpath postgresql.jar \
    --jdbc-url jdbc:postgresql://db/app --jdbc-user toolgate \
    --since 7d --class-name com.acme.SuggestedPolicy -o SuggestedPolicy.java
```

```text
Drafted from 21 of 24 tool calls in 6 sessions
Left out, refused by the recorded policy: 3 (sendEmail.to: 3)
Seen too rarely to suggest, listed for review: 2
```

The draft is a class that supplies a policy, ready for `replay` and `@ToolGateTest`:

```java
.agent("support-chat", a -> a
        // 21 calls in 6 sessions
        .allow("fetchPage", "issueRefund", "sendEmail")
        // fetchPage.url: 1 host
        .rule("fetchPage.url", schemes("https"), hosts("docs.example"))
        // issueRefund.amount: 7 values, largest 5000; limit 40, the largest seen in 3 sessions; ...
        .rule("issueRefund.amount", atLeast("0"), atMost("40").otherwise(OnViolation.DEFER))
        .rule("sendEmail.to", emailDomains("mycompany.com"))
        // At most 6 calls in one session
        .budget(b -> b.toolCalls(10)))
// Seen in fewer than 3 sessions, so not allowed; review each before adding it:
//   agent support-chat, tool deleteAllOrders: 1 call in 1 session
//   agent support-chat, fetchPage.url host evil.example: 1 call in 1 session
```

What it suggests, per agent:

- **Tools** the agent called, and the MCP servers they come from.
- **URL arguments:** the schemes and hosts seen. A URL to an IP address is listed for review, never
  allowed, since rules name hosts.
- **Email arguments:** the recipients' domains, for `emailDomains` (`toolgate-email`). Lists of
  recipients count one by one.
- **Path arguments:** the first two directories, for `under`.
- **Number arguments:** `atMost(limit).otherwise(DEFER)`, and `atLeast("0")` when no value was negative.
  The limit is the 95th percentile, or the largest value seen in `--min-sessions` sessions if that is
  lower, so one session's outlier never sets it.
- **A tool-call budget**, half again the busiest session's, at least 10.

**Review the draft before you use it.** History also holds what an injected model asked for, so:

- A call the recorded policy refused for a real reason, such as an argument rule, is left out. Calls
  refused only because the policy did not list them yet (`allow-list`, `agent.known`, `mcp.servers`,
  `arguments.declared`) count, so monitor mode on a new policy works.
- A tool, host, email domain or path root seen in fewer than `--min-sessions` sessions (3 by default) is
  not allowed, but listed in a comment for review.
- Only these generalizations reach the draft, never an argument's value.

Labels and effects cannot be seen in history, so add them yourself (see [Policies](policies.md#taint)).
The draft starts in monitor mode. From Java, the same is
`PolicySuggestion.builder().build().suggest(events).javaSource()`.

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
