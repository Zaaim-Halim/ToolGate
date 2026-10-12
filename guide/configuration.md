# Spring configuration reference

Every Spring Boot property ToolGate reads, with its default, and the beans it picks up. The policy
itself, including its mode, is Java: a `ToolGatePolicy` bean, never a property.

## `toolgate-spring-ai`

| Property | Default | What it does |
| --- | --- | --- |
| `toolgate.agent` | none | The agent ID for requests whose tool context names none, in an application with one agent. `ToolGateContext.of(...)` overrides it per request. |
| `toolgate.audit.log.enabled` | `true` | One JSON line per event on the `toolgate.audit` logger. |
| `toolgate.audit.micrometer.enabled` | `false` | Decision counter, check timer, dropped-events counter and approval gauges in the application's `MeterRegistry`. Needs `micrometer-core` and a registry. |
| `toolgate.audit.opentelemetry.enabled` | `false` | Decision attributes on the current span. Needs `opentelemetry-api`. |
| `toolgate.approvals.executor.enabled` | `true` | Sweep the approval queue and run calls approved elsewhere, on a schedule. |
| `toolgate.approvals.executor.id` | host name and process ID | This instance's name in the queue. |
| `toolgate.approvals.executor.lease` | `5m` | How long a run may take before its outcome counts as unknown. |
| `toolgate.approvals.executor.interval` | `30s` | How often to sweep and look for calls to run. |
| `toolgate.approvals.metrics.refresh` | `30s` | How often the approval gauges read the queue. |
| `toolgate.approvals.outcome.chat-memory` | `false` | Add a message to the conversation's chat memory when a deferred call is decided or finishes. Needs a `ChatMemory` bean. See [Limitations](limitations.md#the-chat-memory-outcome-message-is-written-for-headless-sessions-too). |
| `toolgate.jdbc.retention` | unset: keep everything | How long audit rows and finished approvals are kept, such as `400d`. Setting it turns on the daily purge. |
| `toolgate.jdbc.session-retention` | `toolgate.jdbc.retention` | How long an idle session and its events, which hold real argument values, are kept. |
| `toolgate.jdbc.purge-interval` | `1d` | How often to purge. |

### Beans it uses

Declare any of these and the auto-configuration uses it; each is optional unless marked.

| Bean | Default | For |
| --- | --- | --- |
| `ToolGatePolicy` | **required**; the application does not start without one | The policy |
| `SessionStore` | in memory | Session state; `JdbcSessionStore` for more than one instance |
| `ApprovalQueue` | in memory | Deferred calls; `JdbcApprovalQueue` for more than one instance |
| `OwnershipResolver` | none | `sameTenant` and `ownedByPrincipal` rules, on arguments and results |
| `HostResolver` | none | Checking where URL hosts resolve |
| `ToolGatePrincipalResolver` | the Spring Security user | Who a request acts for |
| `AuditSink` (any number) | none | Every audit event, such as `JdbcAuditSink` |
| `DecisionObserver` (any number) | none | Each check as it ends, on the request thread |
| `ApprovalListener` (any number) | none | What happens to deferred calls |
| `Clock` | the system clock | Time, for tests |
| `ToolCatalog.Mutable` | in memory, filled as tools are offered | Tool definitions, for pins and declared arguments |

## `toolgate-approvals-web`

| Property | Default | What it does |
| --- | --- | --- |
| `toolgate.approvals.ui.enabled` | `false` | Turns on the console and its REST API. Requires Spring Security. |
| `toolgate.approvals.ui.application` | `ToolGate` | The application's name, shown in the console's header. |
| `toolgate.approvals.notify.console-url` | none | The console's public address, so notifications link to the call. |
| `toolgate.approvals.notify.webhooks[n].url` | none | Where to post, over HTTPS. Needs `toolgate-webhook`. |
| `toolgate.approvals.notify.webhooks[n].format` | `signed-json` | `signed-json`, `slack` or `teams`. |
| `toolgate.approvals.notify.webhooks[n].secret` | none | The shared secret for `signed-json`: at least 32 random characters, from the environment. |
| `toolgate.approvals.notify.webhooks[n].events` | all | Any of `deferred`, `expiring`, `decided`, `recorded`, `finished`, `super-admin-burst`. |
| `toolgate.approvals.notify.email.enabled` | `false` | Email reviewers through the application's `MailSender`. |
| `toolgate.approvals.notify.email.from` | none | The sender address. |
| `toolgate.approvals.notify.email.to` | none | Who hears about every agent's calls, super admin bursts and unknown outcomes. |
| `toolgate.approvals.notify.email.agents.<agent>` | none | Who hears about one agent's calls, besides `to`. |
| `toolgate.approvals.slack.signing-secret` | none | The Slack app's signing secret, which proves a click came through Slack. |
| `toolgate.approvals.slack.app-id` | none | The Slack app's ID; clicks from any other app are refused. |
| `toolgate.approvals.slack.team-id` | none | The Slack workspace's ID; clicks from any other workspace are refused. |
| `toolgate.approvals.slack.response-hosts` | `hooks.slack.com`, `hooks.slack-gov.com` | Hosts a click's `response_url` may point at. |
| `toolgate.approvals.slack.channels[n].webhook-url` | none | One of the Slack app's incoming webhooks, over HTTPS. Turns Slack approval messages on. |
| `toolgate.approvals.slack.channels[n].agents` | all | The agents whose calls go to this channel. |
| `toolgate.approvals.slack.channels[n].tenant`, `.roles` | none | The tenant and reviewer roles of the channel's members: the message shows what such a reviewer may see. Super admin and auditor roles are refused. |
| `toolgate.approvals.slack.channels[n].show-arguments` | `false` | Show the call's masked review view and an Approve button; off, the message only links to the console. |

### Beans it uses

| Bean | Default | For |
| --- | --- | --- |
| `ToolGateReviewerResolver` | the user's name and authorities without `ROLE_`, in no tenant | Who a reviewer is; declare one in a multi-tenant application |
| `ToolGateRequesterDirectory` | the requester as recorded at deferral | Looking up the requester's current roles for the re-check on approval |
| `ToolGateSlackReviewers` | none: no Approve button in Slack | Who a Slack user is, as a reviewer |
