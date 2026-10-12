# Policies

A policy is Java, built once at startup and immutable. Every decision records the policy's hash, so
the audit log says which version decided.

```java
ToolGatePolicy policy = ToolGatePolicy.builder()
        .mode(Mode.ENFORCE)
        .tool("readInvoice", t -> t.labels(PRIVATE_DATA).effect(READ))
        .tool("fetchSupplierPage", t -> t.labels(UNTRUSTED_CONTENT, EXTERNAL_SINK).effect(READ))
        .tool("sendEmail", t -> t.labels(EXTERNAL_SINK).effect(IRREVERSIBLE))
        .tool("draftEmail", t -> t.effect(REVERSIBLE))
        .agent("invoice-processor", a -> a
                .principal(Principal.service("invoice-processor", Tenant.of("acme")))
                .allow("readInvoice", "fetchSupplierPage", "sendEmail", "draftEmail")
                .downgrade("sendEmail", "draftEmail")
                .rule("readInvoice.id", sameTenant("invoice"))
                .rule("fetchSupplierPage.url", schemes("https"), hosts("*.suppliers.example"))
                .rule("sendEmail.to", emailDomains("mycompany.com"))
                .budget(b -> b.toolCalls(200)))
        .build();
```

The examples on this page assume static imports of `ArgumentRules.*`, `ResultRules.*`, `Label.*` and
`Effect.*`.

## Agents and tools

- **Deny by default.** An agent may call only the tools it `allow`s. A tool is named `local/name`, or
  `mcp:server/name` for an MCP tool; `allow("sendEmail")` means the application's own tool.
- **MCP servers** must be listed policy-wide with `.mcpServers("jira", ...)`; any other server's tools
  are denied. A server is named after the connection the application configured, never after what it
  claims.
- **Pins.** `.tool("mcp:jira/search_issues", t -> t.pin(sha256))` denies the tool once its description
  or input schema changes, until someone reviews the change and updates the pin.
- **Principals.** `.principal(...)` gives a headless agent its service identity. Interactive agents
  (`.interactive(true)`) act for the signed-in user, who confirms what the policy holds.
- **Roles.** `.requireRole("issueRefund", "finance")` lets only principals with one of these roles call
  the tool. It is checked right after the allow-list, before any lookup.

## Argument rules

Attached with `.rule("tool.argument", ...)`. A call to a tool with argument rules may pass only the
arguments its schema declares.

| Rule | For |
| --- | --- |
| `hosts`, `schemes`, `ports` | URL arguments, after strict parsing; special-purpose addresses such as cloud metadata are always denied |
| `under`, `notUnder` | Path arguments, after resolving `..` and symlinks |
| `sameTenant(type)`, `ownedByPrincipal(type)` | Record IDs, through the application's `OwnershipResolver` |
| `atMost`, `atLeast`, `totalAtMost` | Numbers; `.otherwise(DEFER)` sends a call over the limit for review instead of denying it |
| `emailDomains` (`toolgate-email`) | Every recipient of an email argument |
| `readOnlySql` (`toolgate-sql`) | A pre-check that a SQL argument is one query |
| `validated(validator)` | Your own `ArgumentValidator` |

The rules are static methods on `ArgumentRules`, `EmailRules` (`toolgate-email`) and `SqlRules`
(`toolgate-sql`).

**Ownership.** `sameTenant` and `ownedByPrincipal` ask the application who a record belongs to, through
an `OwnershipResolver`: give one to the gate's builder (`.ownershipResolver(...)`), or declare it as a
bean on Spring AI. A record the resolver does not know is denied.

```java
OwnershipResolver owners = new OwnershipResolver() {
    @Override
    public Optional<String> tenantOf(String resourceType, String id) {
        return "invoice".equals(resourceType) ? invoices.findTenant(id) : Optional.empty();
    }
};
```

Override `tenantsOf` and `ownersOf` as well when one query can look up a whole list of IDs.

## Taint

Labels say what a tool brings into a session or where it sends data:

- `PRIVATE_DATA`: the result holds private data, such as an invoice or a customer record.
- `UNTRUSTED_CONTENT`: the result holds text someone else wrote, such as a web page, an email or a ticket.
- `EXTERNAL_SINK`: the call sends data out.

Effects say what a call changes: `READ`, `REVERSIBLE`, or `IRREVERSIBLE` (the default).

- **The trifecta.** Once a session holds private data and untrusted content, a call to an external
  sink is blocked (headless agents) or deferred to the user (interactive ones). `.onTrifecta(...)`
  overrides that.
- **Irreversible calls** after untrusted content are deferred for review.
- **Downgrades** soften both: `.downgrade("sendEmail", "draftEmail")` runs the safe tool instead, so a
  job keeps working and a person reviews the draft.
- **Your own rules**: `.whenTainted("sendEmail", UNTRUSTED_CONTENT).then(TaintAction.DEFER)`.
- **Declassifying.** When a person has checked what a session holds, `gate.declassify(sessionId, label,
  justification)` removes a label from it. It is audited with the justification.

## Budgets and denials

`.budget(b -> b.toolCalls(40))` ends the run past 40 tool calls in a session. After five denials in a
session the run ends too; change it with `.defaults(d -> d.denialBudget(n))`.

## Results

`.result("searchInvoices", "items[*].tenantId", matchesTenant())` withholds a tool's output if any
value at that path is not the principal's tenant ID. `matchesPrincipal()` requires the principal's own
ID, and `sameTenant(type)` or `ownedByPrincipal(type)` look record IDs up through the
`OwnershipResolver`, as argument rules do. The model gets a generic message instead of the output.

## Approvals

A deferred call waits in the review queue, in memory or in your database with `toolgate-jdbc`.
`.reviewers("finance")` says who may approve, in the call's own tenant; without it, nobody may, except
the signed-in user of an interactive agent. Nobody may approve their own call anywhere else. An approved
call is re-checked against the policy running at that moment, and runs once. A call nobody decides
expires: after 24 hours for a headless agent, or 10 minutes for an interactive one, whose user is right
there. Change it per agent with `.approvalExpiry(Duration.ofHours(4))`.

Policy-wide, `superAdmins("toolgate-admin")` names who may see and decide every agent's calls in every
tenant, and `auditors("compliance")` who may see them all and decide none. Neither exists unless you
name it. A super admin can never approve their own call, must justify every decision with a comment,
and is audited as a super admin; `superAdminConfirmation()` adds break-glass, where a super admin's
approval waits for a second person. More than 5 super admin decisions in an hour raise the audit event
`toolgate.superadmin.burst` (`superAdminAlert(...)` on the gate's builder changes the threshold). With
the JDBC queue the count covers every instance, so a burst spread behind a load balancer is still seen;
each instance raises it at most once an hour.

Every decision is kept with the call (who, under which access, when, and their comment). An approved
call is `RELEASED`; an executor `claim`s it under a lease, runs it, and reports with `finished`, so the
queue knows whether it ran. If the lease runs out with no outcome, `sweep()` marks it `UNKNOWN`, and
nothing runs it again. Until someone checks the real system and calls `resolve(approvalId, by, ran,
reason)`, new calls to the same tool in that session are denied (rule `approval.unresolved`), so the
agent cannot send a second refund nobody knows about. A reviewer of the call's tenant or a super admin
may resolve; the requester may not.

The adapters do the claiming for you. On Spring AI, `ToolGateApprovals.approve(...)` approves, claims,
runs and reports the call; a scheduled executor (on by default, every 30 seconds) sweeps the queue and
runs calls approved elsewhere, such as from a separate console, so you don't schedule `sweep()`
yourself. Tune it with `toolgate.approvals.executor.id`, `.lease` and `.interval`, or turn it off with
`toolgate.approvals.executor.enabled=false`. On LangChain4j, call `runApproved()`, which also sweeps, on
your own schedule, such as every 30 seconds; without a framework, see
[Using the core directly](core.md).
In a cluster, register tools that need approval as beans (or with `agent(...).tools(...)`), so any
instance can run them.

A review screen shows `reviewItem(approvalId, viewer)`: the action's title, why it waits, where the
session's taint came from (the defence against a request worded to talk the reviewer into it), and the
arguments, shown, masked or hidden by the agent's review view:

```java
.agent("refund-agent", a -> a
        .allow("issueRefund")
        .reviewers("finance")
        .review("issueRefund", r -> r.title("Refund an order").show("order", "amount").mask("iban", Mask.last(4))))
```

Without a review view, arguments the policy has rules on are shown and all others are masked. A
deferral reason that would quote a masked or hidden argument ("480 is above 200") is withheld. The
inbox, `pending(viewer)`, returns the same masked review items, only those the viewer may see, and so does
`history(viewer, ApprovalQuery.decidedSince(since, limit))` for decided calls, newest first. An item's
`mayDecide()` says whether the viewer may take the step its status allows: approve or reject it while
pending, resolve it while its outcome is unknown. Values that could look like something
else carry warnings: a text-direction or invisible character, or a non-ASCII letter, which also flags
legitimate names such as Müller, so reviewers should read it as "check this", not "this is an attack".
Render everything as plain text: the arguments came from the model. Approve with the item's `digest()`,
so the approval names exactly what the reviewer saw. A super admin's or auditor's view and listing are
audited (`toolgate.approval.viewed`, `toolgate.approvals.listed`), and an `ApprovalListener` hears both.

A chat screen can read the calls its own request deferred from the response, to show "Confirm?":
`ToolGateDeferrals.of(chatClient.prompt()...call().chatClientResponse())`. The requester or a super
admin can `cancel` a call nobody decided yet.

`pending()`, without a viewer, returns every tenant's calls: for operators and jobs only, never for a
screen a reviewer sees.

## Modes

- `ENFORCE`: decisions are applied.
- `MONITOR`: every call runs; the audit log records what enforcement would have done.
- `OFF`: tools run as if ToolGate were not there; the emergency switch.

`.ruleMode("taint.trifecta", Mode.MONITOR)` monitors a single rule while the rest is enforced.
