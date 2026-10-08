-- ToolGate approval queue, PostgreSQL. Times are epoch milliseconds (UTC), so no time zone or
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
    principal_roles  TEXT          NOT NULL,
    tool             VARCHAR(1024) NOT NULL,
    args             TEXT          NOT NULL,
    call_at          BIGINT        NOT NULL,
    defer_rule       VARCHAR(512)  NOT NULL,
    defer_reason     TEXT          NOT NULL,
    deferred_at      BIGINT        NOT NULL,
    expires_at       BIGINT        NOT NULL
);
CREATE INDEX toolgate_pending_call_status ON toolgate_pending_call (status, deferred_at);

-- ToolGate session store. One row per session: the state every check reads. The event log keeps
-- every session event, including the calls' REAL ARGUMENT VALUES, unencrypted: protect it like the
-- data it carries, and purge idle sessions (JdbcSessionStore.purgeIdle) to bound how long it is kept.
CREATE TABLE toolgate_session (
    session_id  VARCHAR(512)  NOT NULL PRIMARY KEY,
    version     BIGINT        NOT NULL,
    tool_calls  INT           NOT NULL,
    denials     INT           NOT NULL,
    taint       TEXT          NOT NULL,
    updated_at  BIGINT        NOT NULL
);
CREATE INDEX toolgate_session_updated ON toolgate_session (updated_at);
CREATE TABLE toolgate_session_event (
    session_id  VARCHAR(512)  NOT NULL,
    version     BIGINT        NOT NULL,
    event_type  VARCHAR(32)   NOT NULL,
    event       TEXT          NOT NULL,
    recorded_at BIGINT        NOT NULL,
    PRIMARY KEY (session_id, version)
);
