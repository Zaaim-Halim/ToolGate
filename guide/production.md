# Running in production

A checklist for moving from monitor mode on one instance to enforcement in a real deployment. Read
[Known limitations](limitations.md) first.

## 1. Enforce, one rule at a time

Start in `Mode.MONITOR` and read the audit log until it shows only the decisions you want. Then switch
to `Mode.ENFORCE`, keeping any rule you are not sure of in monitor mode:

```java
ToolGatePolicy.builder()
        .mode(Mode.ENFORCE)
        .ruleMode("taint.trifecta", Mode.MONITOR)
```

To write the first enforcing policy, [draft one](replay.md#suggest) from a week of monitor mode. Before
each policy change, [replay](replay.md) recorded traffic through the new policy, and keep the
policy under test with [`toolgate-test`](testing.md). `Mode.OFF` is the emergency switch: tools run as
if ToolGate were not there.

## 2. Keep state in your database

The default session store and approval queue are in memory: they are lost on restart, and each
instance has its own. With more than one instance, or any approvals, use `toolgate-jdbc`:

```xml
<dependency>
    <groupId>io.github.zaaim-halim.toolgate</groupId>
    <artifactId>toolgate-jdbc</artifactId>
    <version>0.1.0</version>
</dependency>
```

Create the tables with your usual migration tool, from the DDL for your database: `postgresql.sql`,
`mysql.sql` or `h2.sql` under `io/toolgate/jdbc/` in the jar, also returned by
`JdbcApprovalQueue.Dialect.POSTGRESQL.ddl()`. Then, on Spring AI, declare the stores as beans:

```java
@Bean
ApprovalQueue toolGateApprovalQueue(DataSource dataSource, ValueCodec codec) {
    return new JdbcApprovalQueue(dataSource, codec);
}

@Bean
SessionStore toolGateSessionStore(DataSource dataSource, ValueCodec codec) {
    return new JdbcSessionStore(dataSource, codec);
}

@Bean
AuditSink toolGateAuditTable(DataSource dataSource) {
    return new JdbcAuditSink(dataSource);
}
```

Without Spring, pass the same objects to `ToolGate.builder(policy)`.

## 3. Encrypt argument values at rest

Queued calls and the session event log hold the calls' real arguments: amounts, email addresses,
whatever the model passed. Without a codec they are stored as plain text, and ToolGate warns at
startup. Seal them with AES-GCM:

```java
@Bean
ValueCodec toolGateCodec(@Value("${app.toolgate.key}") String base64Key) {
    return ValueCodec.aesGcm("2026-10", Base64.getDecoder().decode(base64Key));   // a 256-bit key
}
```

Each value is bound to its row, so a sealed value copied to another row does not open. To rotate, seal
with the new key and keep opening with the old one:
`ValueCodec.aesGcm("2027-01", newKey).alsoOpening("2026-10", oldKey)`.

Keep the key in your secrets manager, never in source control. The `toolgate` command needs the same
codec (`--codec-class`) to replay an encrypted log.

## 4. Approvals

- Name reviewers per agent (`.reviewers("finance")`). Without them, nobody may approve that agent's
  calls, except the signed-in user of an interactive agent.
- Name `superAdmins(...)` and `auditors(...)` only if you need them, under dedicated roles granted
  through your identity provider with MFA. Consider `superAdminConfirmation()`.
- Register tools that can need approval as beans, so any instance can run an approved call.
- In a multi-tenant application, declare a `ToolGateReviewerResolver` (reviewers' tenants) and a
  `ToolGateRequesterDirectory` (requesters' current roles) for the [console](approvals-console.md).
- Make irreversible tools idempotent on the call's idempotency key (`toolgate.idempotencyKey` in the
  tool context).
- Tell reviewers a call waits: [webhooks, Slack, Teams or email](audit.md#approval-events). To approve
  from Slack, map Slack users with `ToolGateSlackReviewers`, and turn on `show-arguments` only for channels
  whose members may see the values ([Approving in Slack](audit.md#approving-in-slack)).

## 5. Watch it

With `toolgate.audit.micrometer.enabled=true`, alert on:

| Metric | Alert when |
| --- | --- |
| `toolgate.audit.dropped` | it increases: the audit trail has gaps (enforcement is unaffected) |
| `toolgate.decisions` with `outcome=deny` | it spikes: an injection attempt, or a policy that is too strict |
| `toolgate.decisions` with `outcome=terminate` | it spikes: runs ended by budgets or the denial budget |
| `toolgate.approvals.oldest` | it nears your approval expiry: reviewers are not keeping up |
| `toolgate.approvals.unknown` | it is above zero: someone must check the real system and resolve the call |
| `toolgate.check.duration` | its 99th percentile grows: usually a slow session store |

Send the audit trail where your security team looks: the `toolgate.audit` logger, an `AuditSink` for
your SIEM, or the `toolgate_audit` table and its read views. See [Audit, metrics and traces](audit.md).

## 6. Retention

Rows are kept until you delete them. On Spring AI:

```properties
toolgate.jdbc.retention=400d
toolgate.jdbc.session-retention=30d
```

Session events hold real argument values, so keep them no longer than you need for replay.

## 7. Hardening checklist

- [ ] Every chat client comes from Spring Boot's `ChatClient.Builder` or `ToolGateChatClients`, and
      every chat model is the auto-configured bean
      ([why](limitations.md#spring-ai-chat-clients-and-models-built-by-hand)).
- [ ] Every tool that reads text someone else wrote is labeled `UNTRUSTED_CONTENT`.
- [ ] Every tool that sends data out is labeled `EXTERNAL_SINK`, and irreversible ones have a downgrade
      or a reviewer.
- [ ] Every MCP server is listed with `mcpServers(...)`, and its tools are pinned.
- [ ] Tools that fetch URLs use `SafeHttp`; tools that run model-written SQL use `SafeJdbc` with a
      read-only role.
- [ ] A `ValueCodec` encrypts stored arguments.
- [ ] Reviewer and super admin roles come from the identity provider, with MFA.
- [ ] The console, if enabled, is behind your normal sign-in, and a proxy forwards the original host.
- [ ] Alerts are set on the metrics above.
