-- ToolGate approval queue, H2. Times are epoch milliseconds (UTC), so no time zone or
-- timestamp type differs between databases. Arguments are the call's real values, as JSON: protect
-- this table like the data it carries.
CREATE TABLE toolgate_pending_call (
    approval_id      VARCHAR(512)  NOT NULL PRIMARY KEY,
    status           VARCHAR(16)   NOT NULL,
    call_id          VARCHAR(512)  NOT NULL,
    session_id       VARCHAR(512)  NOT NULL,
    agent_id         VARCHAR(512)  NOT NULL,
    principal_kind   VARCHAR(16)   NOT NULL,
    principal_id     VARCHAR(512)  NOT NULL,
    principal_tenant VARCHAR(512),
    principal_roles  CLOB          NOT NULL,
    tool             VARCHAR(1024) NOT NULL,
    args             CLOB          NOT NULL,
    call_at          BIGINT        NOT NULL,
    defer_rule       VARCHAR(512)  NOT NULL,
    defer_reason     CLOB          NOT NULL,
    deferred_at      BIGINT        NOT NULL,
    expires_at       BIGINT        NOT NULL
);
CREATE INDEX toolgate_pending_call_status ON toolgate_pending_call (status, deferred_at);
