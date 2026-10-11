# Known issues

Limits we know about and mean to fix. Each entry says what happens, why, and the direction of the fix.

## Taint rules hold nearly every send that follows untrusted content

**Found:** October 11, 2026, by the trifecta trigger-rate measurement (`TrifectaRateTest`, results in the README).

**What happens.** With the default policy, every demo session that reads untrusted content and then calls an
external sink is held or downgraded: all 67 of 67 ticket replies, 47 of 62 inbox sends, 40 of 100 invoice emails.
`taint.irreversible` matters as much as the trifecta: it catches sends after untrusted content even without private
data (35% of ticket sessions, 25% of inbox sessions). Headless agents keep working only because their sends become
drafts; the interactive inbox assistant asks its user in 47% of sessions.

**Why.** Taint is per session and per tool: a tool labeled `UNTRUSTED_CONTENT` taints everything it returns, so a
ticket's `customer` field, set by the ticket system, is as untrusted as the body the customer wrote.

**Direction of the fix.**
- Bound arguments (planned for 0.2.0) would let 65 of the 67 held ticket replies run, but only with trust per result
  field: `replyToCustomer.customerId` bound to `readTicket.customer`. Bound to `lookupCustomer.id` instead, an
  injected ticket that makes the model look up another customer first would run. So bound arguments need labels per
  result field, not only per tool.
- They help none of the inbox assistant's held sends: the recipient comes from the untrusted message itself. For that
  agent, confirmation by the user remains the model; tuning when it asks is the open question.
- Possible tuning to evaluate: the `task` scope, and whether `taint.irreversible` should downgrade or defer by default
  for interactive agents.

The bound-argument figures are analysis, not measurement; re-run `TrifectaRateTest` once any of this lands.
