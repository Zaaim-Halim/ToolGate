# Audit, metrics and traces

Every decision ToolGate makes can be recorded in five places. The log is on by default; the rest
are off until you add them.

| Where | What you get | How |
| --- | --- | --- |
| Log (default) | One JSON line per event on the `toolgate.audit` logger | Nothing to do |
| Your own sink | Every event, for a SIEM, a database table or a queue | An `AuditSink` |
| Your database | Every event as a row, read views for dashboards, approval figures | `JdbcAuditSink`, `ToolGateQueries` |
| Micrometer | Decision counter, check timer, approval gauges | `MicrometerAuditSink`, `MicrometerCheckTimer`, `MicrometerApprovalMetrics` |
| OpenTelemetry | Decision attributes on the current span, an event for each refusal | `OpenTelemetryDecisions` |

## The log

```json
{"event":"toolgate.decision","agent":"support-chat","tool":"local/getInvoice","args":{"id":"text(7)"},"decision":"deny","rule":"getInvoice.id","computed":"deny","computedRule":"getInvoice.id","mode":"enforce","budget":{"toolCalls":2,"limit":40},...}
```

Spring Boot routes the logger to its own logging, so the lines go wherever your logs already go;
filter on the logger name `toolgate.audit`. Argument values are never logged, only their type and
size, or keyed hashes if you set `argumentHashKey`. Deny reasons are logged in full and can quote the
value that was denied.

## Your own sinks

Each sink gets every event, on one background thread, never on the path of a tool call. Adding a sink
keeps the log; a failing sink keeps nothing from the others.

```java
ToolGate gate = ToolGate.builder(policy)
        .auditSink(mySiemSink)
        .auditSink(myDatabaseSink)
        .build();
```

Call `noDefaultLog()` to turn the log off. On Spring AI, declare each sink as an `AuditSink` bean, and
set `toolgate.audit.log.enabled=false` to turn the log off. `event.toJson()` gives a sink the same JSON
line the log writes.

## In your database

With `toolgate-jdbc`, the audit trail and the approval queue live in your database, where your own
screens, reports and dashboards can read them. The DDL for H2, PostgreSQL and MySQL
(`JdbcApprovalQueue.Dialect.POSTGRESQL.ddl()`) creates every ToolGate table and the read views.

```java
ToolGate gate = ToolGate.builder(policy)
        .approvalQueue(new JdbcApprovalQueue(dataSource, codec))
        .sessionStore(new JdbcSessionStore(dataSource, codec))
        .auditSink(new JdbcAuditSink(dataSource))
        .build();
```

On Spring AI, declare the three as beans. `JdbcAuditSink` writes one row per event into
`toolgate_audit`: the event's JSON line, as the log has it, plus columns to query by (kind, session,
approval, agent, actor, tenant, tool, outcome, rule, mode). Like any sink it runs off the request path;
a database outage drops rows, counted in `toolgate.audit.dropped`, and never slows a decision.

**Read views.** For dashboards and SQL reports, read the `toolgate_v1_*` views rather than the tables.
Their columns stay the same across ToolGate versions (a change comes as `toolgate_v2_*`), and they
leave out everything sealed or able to quote an argument: payloads, outcomes, comments, reasons and
session details. Times are epoch microseconds, UTC.

| View | One row per |
| --- | --- |
| `toolgate_v1_approvals` | Deferred call: status, agent, principal, tool, rule, deferred, expires, executor, finished |
| `toolgate_v1_approval_decisions` | Decision on a deferred call: kind, who, access, when |
| `toolgate_v1_decisions` | Gate decision: call, session, agent, principal, tool, outcome, rule, mode, policy |
| `toolgate_v1_sessions` | Session: version, tool calls, denials, last update |

**From Java.** `ToolGateQueries` reads the same data:

```java
var queries = new ToolGateQueries(dataSource);
ApprovalStats week = queries.stats(Duration.ofDays(7));   // waiting, oldest, unknown, by status, p50/p95 time to decide
List<AuditRow> trail = queries.audit(AuditQuery.latest().approval(approvalId));
List<AuditRow> denied = queries.audit(AuditQuery.latest().kinds("DECIDED").outcome("deny").since(yesterday));
```

Neither the views nor `ToolGateQueries` filter by viewer or tenant: they are for operators. Show them
only to people who may see every tenant, or filter by `principal_tenant` yourself. Reviewers see their
calls through the console or `pending(viewer)`.

**Retention.** Rows are kept until you delete them. `JdbcAuditSink.purgeBefore(cutoff)` deletes old
audit rows, `JdbcApprovalQueue.purgeBefore(cutoff)` deletes calls that are over (executed, failed,
refused, rejected, expired, cancelled; never one waiting, approved, running or with an unknown
outcome), and `JdbcSessionStore.purgeIdle(idle)` deletes idle sessions. On Spring AI, one setting
purges them all daily:

```properties
toolgate.jdbc.retention=400d
toolgate.jdbc.session-retention=30d   # optional: session events hold real argument values
```

400 days covers a yearly audit with a margin; regulated teams often keep longer.

## Micrometer

Add `io.micrometer:micrometer-core` to your application; ToolGate does not bring it.

On Spring AI, with a `MeterRegistry` bean (`spring-boot-starter-actuator` gives you one):

```properties
toolgate.audit.micrometer.enabled=true
```

Without Spring:

```java
ToolGate gate = ToolGate.builder(policy)
        .auditSink(new MicrometerAuditSink(registry, policy))
        .decisionObserver(new MicrometerCheckTimer(registry))
        .build();
```

- `toolgate.decisions` (counter): tags `outcome` and `rule` (what was applied), `computed` and
  `computedRule` (what enforcement does; in monitor mode, `outcome` is always `allow`), `agent` and
  `tool`. A tool the agent's policy does not list is tagged `unlisted`, since a model can call tools
  by any name and each name would become its own time series.
- `toolgate.check.duration` (timer): tags `outcome` and `agent`. It includes the session store, so a
  slow database shows here.
- `toolgate.audit.dropped` (counter, Spring AI only): audit events a sink missed and observer calls
  that failed. Alert on any increase: enforcement is unaffected, but the audit trail has gaps. Without
  Spring, register `ToolGate::droppedAuditEvents` as a `FunctionCounter` yourself.

**Approvals.** `MicrometerApprovalMetrics` shows whether reviewers keep up. On Spring AI it comes with
`toolgate.audit.micrometer.enabled=true`; without Spring, give it the gate's queue and add it as a sink:

```java
var metrics = new MicrometerApprovalMetrics(registry, queue, Duration.ofSeconds(30));
ToolGate gate = ToolGate.builder(policy).approvalQueue(queue).auditSink(metrics).build();
```

- `toolgate.approvals.pending` (gauge, tag `agent`): calls waiting for a decision.
- `toolgate.approvals.oldest` (gauge, seconds): how long the oldest one has waited. Alert when it nears
  your expiry.
- `toolgate.approvals.unknown` (gauge): approved calls whose run has an unknown outcome. Alert on any:
  someone must check the real system and resolve them.
- `toolgate.approvals.decided` (counter, tags `status` and `agent`): what became of deferred calls.
- `toolgate.approvals.latency` (timer, tag `status`): from deferral to release or rejection.

The gauges read the queue every 30 seconds (`toolgate.approvals.metrics.refresh`) on a background
thread, so a scrape never waits on the database.

## OpenTelemetry

Add `io.opentelemetry:opentelemetry-api` and a tracing setup that makes spans current, such as
Spring Boot's with `micrometer-tracing-bridge-otel`. ToolGate uses only the API.

On Spring AI:

```properties
toolgate.audit.opentelemetry.enabled=true
```

Without Spring: `ToolGate.builder(policy).decisionObserver(new OpenTelemetryDecisions())`.

Each check sets attributes on the current span: `toolgate.decision`, `toolgate.rule`, `toolgate.agent`,
`toolgate.tool`, `toolgate.session` and `toolgate.mode`, plus `toolgate.computed.decision` and
`toolgate.computed.rule` when monitor mode applied something else. ToolGate checks a call inside the
adapter's tool callback, so with tracing set up the current span is usually the framework's tool-call
span; without one, attributes land on whatever span is current, and a later check under the same span
overwrites them. A call the policy refuses also adds the event `toolgate.denied`, with the rule
and `toolgate.enforced` (false in monitor mode). Deny reasons are left out of traces, since they can
quote the denied value; the log has them.

## Approval events

An `ApprovalListener` hears what happens to deferred calls: `deferred`, `expiringSoon`, `decided`
(approved, refused, rejected, cancelled, expired), `recorded` (a super admin's approval waiting for a
second person), `finished` (executed, failed or unknown) and `superAdminBurst`. It is an audit sink
underneath, so it runs off the request path, and its events carry no argument values: link to the
review screen instead of describing the call. On Spring AI, declare it as an `ApprovalListener` bean.

`expiringSoon` comes from `sweep()` (every 30 seconds on Spring AI), once per call across every
instance, when its expiry is within an hour, or a quarter of its life if that is sooner. Change it with
`expiryWarning(Duration)` on the gate's builder, or turn it off with `noExpiryWarning()`.

### Webhooks, Slack and Teams

`toolgate-webhook` posts these events over HTTPS, with nothing but the JDK. On Spring, with
`toolgate-approvals-web`, configure them in properties; any number, each with its own events:

```properties
toolgate.approvals.notify.console-url=https://refunds.example.com/toolgate/approvals/
toolgate.approvals.notify.webhooks[0].url=https://ops.example.com/hooks/toolgate
toolgate.approvals.notify.webhooks[0].secret=${TOOLGATE_WEBHOOK_SECRET}         # signed-json, 32+ random characters
toolgate.approvals.notify.webhooks[1].url=${TEAMS_WORKFLOW_URL}
toolgate.approvals.notify.webhooks[1].format=teams                              # or slack
toolgate.approvals.notify.webhooks[1].events=deferred,expiring
```

Events are `deferred`, `expiring`, `decided`, `recorded`, `finished` and `super-admin-burst`, all by
default. A Slack or Teams URL is itself the secret: keep it in the environment, not in source control.
For Teams, create a workflow from the template "Post to a channel when a webhook request is received"
and use its URL; ToolGate posts an Adaptive Card with a button to the console.

Without Spring, or to configure one in code:

```java
ApprovalWebhook webhook = ApprovalWebhook.signed(URI.create("https://ops.example.com/hooks/toolgate"), secret)
        .consoleUrl("https://refunds.example.com/toolgate/approvals/")
        .build();
ToolGate.builder(policy).approvalListener(webhook).build();

// Or a Slack incoming webhook, or a Teams workflow, for a reviewers' channel:
ApprovalWebhook.slack(URI.create(slackUrl)).consoleUrl(consoleUrl)
        .events(ApprovalWebhook.Event.DEFERRED, ApprovalWebhook.Event.EXPIRING).build();
ApprovalWebhook.teams(URI.create(teamsUrl)).consoleUrl(consoleUrl).build();
```

A signed delivery is flat JSON (`type`, `time`, `approvalId`, `agent`, `tool`, `tenant`, `status`, `by`,
`access`, `expiresAt`, `link`), with no argument values, reasons or comments. The header
`ToolGate-Signature: t=<seconds>,v1=<HMAC-SHA256 of "t.body">` proves it came from ToolGate; check it
with `WebhookSignature.verify(secret, header, body, Duration.ofMinutes(5))`, which also turns away a
captured delivery replayed later. `ToolGate-Delivery` stays the same across retries, so deduplicate on
it. Use a random secret of at least 32 bytes.

Deliveries wait in a bounded queue and are posted in order by one background thread, retrying
timeouts, 408, 429 and 5xx with a growing delay (5 attempts from 1 second, `retries(...)`). One that
still fails is dropped and counted in `webhook.dropped()`. Redirects are not followed, and the URL
must be HTTPS. Close the webhook when the application stops; on Spring, declare it as a bean and Spring
closes it.

### Approving in Slack

With `toolgate-approvals-web` on Spring MVC, a Slack app can carry the call itself, and an **Approve**
button. Rejecting needs a reason, so it stays in the console, one click away.

1. Create a Slack app. Turn on **Incoming Webhooks** and add one per channel; turn on **Interactivity** with
   the request URL `https://<your application>/toolgate/slack/actions`.
2. Map Slack users to reviewers. ToolGate never guesses this from a name or an email:

   ```java
   @Bean
   ToolGateSlackReviewers slackReviewers(UserDirectory users) {
       return (teamId, userId) -> users.findBySlackId(userId)
               .map(user -> Principal.user(user.id(), Tenant.of(user.tenant()), user.roles().toArray(String[]::new)));
   }
   ```

3. Configure the app and its channels:

   ```properties
   toolgate.approvals.notify.console-url=https://refunds.example.com/toolgate/approvals/
   toolgate.approvals.slack.signing-secret=${SLACK_SIGNING_SECRET}
   toolgate.approvals.slack.app-id=A0123ABCD
   toolgate.approvals.slack.team-id=T0123ABCD
   toolgate.approvals.slack.channels[0].webhook-url=${SLACK_FINANCE_WEBHOOK}
   toolgate.approvals.slack.channels[0].agents=refund-agent
   toolgate.approvals.slack.channels[0].tenant=acme
   toolgate.approvals.slack.channels[0].roles=finance
   toolgate.approvals.slack.channels[0].show-arguments=true
   ```

What a message shows is decided per channel. **By default, no argument value reaches Slack**: the message
says which agent asks to run which tool, with a link to the console. With `show-arguments=true` it shows
what the console shows a reviewer of the channel's tenant and roles: the action's title, why it waits,
where the session's taint came from, and each argument as the review view shows, masks or hides it, with
the console's warnings on suspicious characters. Shown values reach Slack, so turn it on only for
channels whose members may see them.

How a click is checked:

- **Signed by Slack.** The signature is checked over the raw request, within five minutes, before anything
  is read; then the app ID and workspace ID. A click from another workspace, such as a Slack Connect
  partner's, is refused.
- **Bound to what was shown.** The button carries the digest of the view, so a call whose view changed
  since is not approved.
- **Decided as the reviewer.** The Slack user is mapped with `ToolGateSlackReviewers`, then ToolGate
  decides as from the console: the reviewer's tenant and roles, never their own call, the re-check on
  release. The decision is audited with the Slack user ID. Super admins are sent to the console, where
  they give a justification.
- **Released, then run.** An approval from Slack releases the call; the approval executor runs it within
  its interval (30 seconds by default), so Slack gets its answer in time. The message is updated through
  Slack's `response_url`, and only if that URL is on one of `toolgate.approvals.slack.response-hosts`.
- **Text stays text.** Every value is Slack `plain_text`: a model-written `<!channel>`, mention or link is
  shown, never acted on. A message whose view Slack would cut, past 3,000 characters in one value or 50
  blocks, has no Approve button.

The endpoint authenticates by Slack's signature, not by the application's sign-in, so ToolGate tells
Spring Security to leave that one path, `POST /toolgate/slack/actions`, to it; every other path stays as
your security configuration has it. Without a `ToolGateSlackReviewers` bean, or without the signing
secret, app ID and workspace ID, there is no endpoint and no Approve button. Approving in Slack is for
Spring MVC; on WebFlux, messages link to the console.

### Email

With `toolgate-approvals-web` and a `MailSender` (`spring-boot-starter-mail` and `spring.mail.host`):

```properties
toolgate.approvals.notify.email.enabled=true
toolgate.approvals.notify.email.from=toolgate@refunds.example.com
toolgate.approvals.notify.email.to=ops@example.com                       # every agent, bursts, unknown outcomes
toolgate.approvals.notify.email.agents.refund-agent=finance@example.com  # one agent's reviewers
toolgate.approvals.notify.console-url=https://refunds.example.com/toolgate/approvals/
```

Reviewers get a mail when a call waits, expires soon or needs a second person; the `to` list also gets
unknown outcomes and super admin bursts. Mails carry the agent, the tool and a link, never argument
values, and are sent on their own thread.

Links open the request in the console (`?id=…`); the console still checks that whoever follows the link
may see it.

### Telling the agent

To tell the agent what became of the calls it asked for, set `toolgate.approvals.outcome.chat-memory=true` (with a `ChatMemory` bean): ToolGate adds
a message it writes itself, never the tool's output, to the conversation, so the agent knows on the
user's next turn. It suits chat agents, whose session is the conversation ID.

## Your own observers

A `DecisionObserver` sees each check as it ends, on the thread that asked for it, with the decision
and how long the check took. It runs on the request path, so keep it fast and never block in it. A
failing observer never changes a decision; it is counted in `droppedAuditEvents()`. On Spring AI,
declare it as a `DecisionObserver` bean.
