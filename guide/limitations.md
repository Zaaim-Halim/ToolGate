# Known limitations

What ToolGate does not do yet, or does only in part, as of 0.1.0. Each entry says what happens, what to
do about it today, and the direction of the fix. Read this before relying on ToolGate in production.

## What ToolGate does not cover

- **What a tool does inside.** ToolGate decides whether a call may run, with which arguments. If an
  allowed tool misbehaves with allowed arguments, ToolGate does not see it. The helpers (`SafeHttp`,
  `SafeJdbc`) check inside the tool for HTTP and SQL; for everything else, the tool is yours.
- **Stored poison.** Taint is per session. A document saved last week by an attacker and read today is
  untrusted only if the tool that reads it is labeled `UNTRUSTED_CONTENT`. Label every retrieval tool
  that way.
- **Identity.** Principals and roles come from your application. Anyone who can make it hand ToolGate a
  principal with a reviewer or super admin role decides as that role. Grant those roles through your
  identity provider, with MFA, under dedicated role names, never a general `ADMIN`, and watch the audit
  for `"access":"super_admin"`.

## Spring AI: tools a hand-built `ChatClient` runs unchecked

`ChatClient.create(model)` and `ChatClient.builder(model)` create their own tool calling manager, which
ToolGate never sees. Tool beans with `@Tool` methods are still guarded: if such a client runs one, the
guard refuses it (enforce mode) or logs it (monitor mode). Still **unchecked** in a hand-built client:
tools passed as plain objects (`.tools(new MyTools())`), function callbacks, MCP tools, and tool classes
declared `final`, which cannot be proxied (ToolGate warns about these at startup).

**Today:** build every chat client from Spring Boot's `ChatClient.Builder`, or with
`ToolGateChatClients.builder(model)`.

## Taint holds most sends that follow untrusted content

With the default policy, nearly every session that reads untrusted content and then calls an external
sink is held or downgraded, because a tool labeled `UNTRUSTED_CONTENT` taints everything it returns,
including fields set by a trusted system, such as a ticket's customer ID. The measured rates are in the
[README](../README.md#trifecta-trigger-rate).

**Today:** use downgrades (`.downgrade("sendEmail", "draftEmail")`) so headless agents keep working, and
`ruleMode("taint.trifecta", Mode.MONITOR)` to measure before enforcing. **Planned:** arguments bound to
specific fields of earlier results.

## Totals are per session

`totalAtMost("500")` sums the calls of one session. Refunds spread over several sessions are not
summed. **Planned:** totals per principal and per time window.

## Retried tool calls are not deduplicated

A model or framework that retries a call gets a second decision and, if allowed, a second run. Matching
on tool and arguments would also block legitimate repeats.

**Today:** an approved call carries an idempotency key (`toolgate.idempotencyKey` in the tool context);
make irreversible tools idempotent on their own keys.

## Approvals

- **Not built yet:** four-eyes approval for reviewers (several approvals per call), approve with edits,
  step-up re-authentication before a decision, a standalone console for headless services, and
  approval commands in the CLI. Super admins can already be required to get a second person's
  confirmation (`superAdminConfirmation()`).
- **Tools offered only with one request.** An approved call runs on whichever instance claims it, but
  only an instance that has the tool can. A tool passed with a single request
  (`.tools(new RefundTools())` on a `ChatClient` call) lives in the memory of the instance that deferred
  the call: after a restart, or on another instance, it cannot run and waits until it expires. Register
  tools that can need approval as beans, or with `agent(...).tools(...)` on LangChain4j.
- **The re-check uses the requester as recorded**, unless you declare a `ToolGateRequesterDirectory`:
  the console cannot look up another user's current roles, so a role revoked since the deferral does not
  refuse the release.
- **Pending notifications are kept in memory.** Webhook and mail deliveries not yet sent are lost if the
  application stops abruptly. The call itself is safe in the queue and still shows in the console.
  Alert on `toolgate.approvals.oldest` nearing your expiry to catch any missed notification.

### The chat-memory outcome message is written for headless sessions too

With `toolgate.approvals.outcome.chat-memory=true`, ToolGate also writes the outcome message for a
headless request's own session (`request-<uuid>`), which no conversation reads; an in-memory chat
memory keeps it until restart. Turn the setting on only in applications whose agents are chats.

## Console behind a proxy

A decision is accepted only if the browser's `Origin` matches the `Host` it addressed. A proxy that
rewrites `Host` without forwarding the original makes every decision fail with 403. Set
`server.forward-headers-strategy=framework` (or `native`).

## Database

- **Renamed tables.** `JdbcApprovalQueue` can use a table name of your choosing, but the `toolgate_v1_*`
  views and `ToolGateQueries` read the default names. With a renamed table, create the views by hand
  with the same columns.
- **Tested databases.** The stores and SafeJdbc are tested on H2, PostgreSQL 17 and MySQL 9.6. MariaDB
  and other versions are expected to work but are not tested.

## LangChain4j

- **Compensating actions** (`@CompensateFor`) are not discovered for tools registered through ToolGate.
- **Tool search** (`toolSearchStrategy`): its search tools are not registered through ToolGate, so a call
  to one ends the run.
- **MCP resources as tools** (`McpToolProvider.resourcesAsToolsPresenter`): their server cannot be
  identified, so they are named `mcp:unattributed/...` and always denied.
- **A hook set after ToolGate's** replaces it: an AI service keeps a single `beforeToolExecution` hook.
  Pass your own hook to ToolGate with `.hook(...)`.

## Versions

0.1.0 is tested with Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, LangChain4j 1.22.0, PostgreSQL 17 and
MySQL 9.6. The adapters
rely on framework internals that other versions may change. Every API may change before 1.0.
