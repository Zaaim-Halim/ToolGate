# The approvals console

A review screen for deferred calls, for Spring MVC and Spring WebFlux applications with Spring
Security. Reviewers open `/toolgate/approvals/`, see the calls they may decide, masked by the policy's
review views, and approve, reject or withdraw them. The same actions are a REST API at
`/toolgate/api/approvals`, for your own screens.

## 1. Add the module and turn it on

```xml
<dependency>
    <groupId>io.github.zaaim-halim.toolgate</groupId>
    <artifactId>toolgate-approvals-web</artifactId>
    <version>0.1.0</version>
</dependency>
```

```properties
toolgate.approvals.ui.enabled=true
toolgate.approvals.ui.application=Refund desk   # shown in the header
```

Adding the module exposes nothing until `enabled=true`. With it on, the application refuses to start
without Spring Security (`spring-boot-starter-security`): a console that decides refunds must never be
open.

## 2. Sign-in and roles

Reviewers sign in however your application already signs users in: form login, OIDC, SAML. The console
adds no login of its own, and every endpoint refuses anyone not signed in, even if your security rules
forgot `/toolgate/**`.

Who a reviewer is comes from a `ToolGateReviewerResolver`. The default takes the user's name and their
authorities without `ROLE_` as roles, **in no tenant**. A reviewer's role counts only in their own
tenant, so in a multi-tenant application declare one:

```java
@Bean
ToolGateReviewerResolver reviewers(UserDirectory users) {
    return authentication -> {
        var user = users.find(authentication.getName());
        return Principal.user(user.id(), Tenant.of(user.tenant()), user.roles().toArray(String[]::new));
    };
}
```

Roles match the policy: `reviewers("finance")` for an agent's reviewers, `superAdmins(...)` and
`auditors(...)` policy-wide.

When a call is approved, ToolGate re-checks it with its principal as it is now. By default that is the
principal as recorded when the call was deferred; declare a `ToolGateRequesterDirectory` that looks your
users up, so a role revoked since then counts.

## 3. What keeps it safe

- **Request forgery.** Every decision must be JSON, carry the header `X-ToolGate-Request: 1`, and, when
  the browser names its origin, come from the same host. A form on another site cannot set the header,
  and a script on another site would need CORS, which the API never allows. This holds even if your
  application turned Spring Security's CSRF protection off.
- **Headers.** Every response under `/toolgate/` has a strict Content Security Policy (only the
  console's own script and styles, `frame-ancestors 'none'`), `X-Frame-Options: DENY` and
  `Cache-Control: no-store`. If your application also writes these headers through Spring Security,
  check the result in a browser: whichever filter writes last wins, so keep `frame-ancestors 'none'` and
  `script-src 'self'` in your own policy.
- **Proxies.** Decisions compare the browser's `Origin` with the `Host` it addressed, so a proxy that
  ends TLS works as is. A proxy that rewrites `Host` needs `server.forward-headers-strategy=framework`
  (or `native`), or every decision is refused as cross-origin.
- **Identity of the tool run.** An approved tool runs without the reviewer's Spring Security identity:
  the reviewer's authorities never leak into it. The call's own principal is in its tool context.
- **Text only.** The console renders every value as text: arguments come from the model, and the model
  may be steered by an attacker. Values that could look like something else carry a warning.
- **Digest.** An approval carries the digest of what the reviewer saw, so a changed view is refused
  rather than approved.
- **Blocking work off event loops.** On WebFlux, every call runs on Reactor's bounded elastic
  scheduler, since ToolGate's stores, such as JDBC, block.

## 4. The REST API

| Method and path | Body | Answer |
|---|---|---|
| `GET /toolgate/api/approvals` | | The calls you may see, masked |
| `GET /toolgate/api/approvals/item?id=…` | | One call; 404 also when you may not see it |
| `GET /toolgate/api/approvals/me` | | Who you are signed in as, and the application's name |
| `POST /toolgate/api/approvals/decisions` | `{approvalId, kind, comment, digest, ran}` | `{outcome, reason, status}` |

`kind` is `approve` (with `digest`), `reject`, `cancel` or `resolve` (with `ran`); a comment is required
except for a reviewer's approval. Outcomes: `released`, `awaiting-confirmation`, `refused`,
`not-permitted`, `already-decided`, `expired`, `done`, `not-done`. HTTP errors are kept for the request
itself: 400 invalid, 401 not signed in, 403 refused by the forgery checks, 404 not visible.

## 5. Testing it

`mvn -pl toolgate-approvals-web -Pe2e verify` also runs the browser tests (Playwright for Java), which
download a browser on first run: an attacker's text stays text, masked values stay masked, and a
reviewer can approve.
