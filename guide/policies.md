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

## Agents and tools

- **Deny by default.** An agent may call only the tools it `allow`s. A tool is named `local/name`, or
  `mcp:server/name` for an MCP tool; `allow("sendEmail")` means the application's own tool.
- **MCP servers** must be listed with `.mcpServers("jira", ...)`; any other server's tools are denied.
  A server is named after the connection the application configured, never after what it claims.
- **Pins.** `.tool("mcp:jira/search_issues", t -> t.pin(sha256))` denies the tool once its description
  or input schema changes, until someone reviews the change and updates the pin.
- **Principals.** `.principal(...)` gives a headless agent its service identity. Interactive agents
  (`.interactive(true)`) act for the signed-in user, who confirms what the policy holds.

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

## Budgets and denials

`.budget(b -> b.toolCalls(40))` ends the run past 40 tool calls in a session. After five denials in a
session the run ends too; change it with `.defaults(d -> d.denialBudget(n))`.

## Results

`.result("searchInvoices", "items[*].tenantId", matchesTenant())` withholds a tool's output if any
value at that path belongs to another tenant. The model gets a generic message instead.

## Approvals

A deferred call waits in the review queue, in memory or in your database with `toolgate-jdbc`.
`.reviewers("finance")` says who may approve; without it, nobody may, except the signed-in user of an
interactive agent. An approved call is re-checked against the policy running at that moment, and runs
once.

## Modes

- `ENFORCE`: decisions are applied.
- `MONITOR`: every call runs; the audit log records what enforcement would have done.
- `OFF`: tools run as if ToolGate were not there; the emergency switch.

`.ruleMode("taint.trifecta", Mode.MONITOR)` monitors a single rule while the rest is enforced.
