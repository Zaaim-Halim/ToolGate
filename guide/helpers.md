# Helpers for tool authors

A policy decides whether a call may run. These modules check, inside the tool, that it does what the
check allowed.

## Email recipients: `toolgate-email`

```java
.rule("sendEmail.to", emailDomains("mycompany.com", "*.mycompany.com"))
```

Every recipient must be at an allowed domain. Only plain `local@domain` addresses, optionally with a
simple display name, are accepted; quoted local parts, groups, routes and other forms mail libraries
read differently are denied.

## SQL: `toolgate-sql` and `toolgate-safe-jdbc`

`readOnlySql()` is a pre-check that only denies: one `SELECT`, no comment markers, no second
statement, and none of the words `INSERT UPDATE DELETE MERGE UPSERT INTO CREATE DROP ALTER TRUNCATE
GRANT REVOKE COPY CALL EXEC EXECUTE LOCK SHARE` anywhere, even in a string. It denies some harmless
queries; it fails closed.

The database enforces the rest. Run the query through SafeJdbc:

```java
Rows rows = SafeJdbc.query(dataSource, sql, Limits.defaults().maxRows(50));
```

SafeJdbc re-runs the pre-check, runs the query in a transaction the database holds to read-only
(PostgreSQL, MySQL, MariaDB), always rolls back, and returns at most `maxRows` rows of typed values.
On other databases use `queryWithReadOnlyRole` with a role that may only read. Grant that role only
what the tool needs: a read-only transaction does not stop functions with side effects or reads of
server files.

## HTTP: `toolgate-safe-http`

```java
CloseableHttpClient http = SafeHttp.builder()
        .rules(hosts("*.vendor.example"), schemes("https"))
        .build();
```

An Apache HttpClient 5 client. Every request and every redirect is checked against the rules before
anything is resolved or sent. Each host is resolved once; if any address it resolves to is
special-purpose, such as loopback, a private network or the cloud metadata service, the request fails
with `RefusedDestinationException`; otherwise the client connects to exactly those addresses, so a
name cannot pass the check with one address and connect to another. Proxies are never used.

To test a policy without a model, see [Testing policies](testing.md).
