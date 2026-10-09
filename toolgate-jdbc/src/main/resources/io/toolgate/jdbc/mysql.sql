-- ToolGate approval queue, MySQL. Requires utf8mb4. Times are epoch microseconds (UTC), so no time zone or
-- timestamp type differs between databases. payload holds the call's real arguments and the deferral
-- reason, as JSON, sealed by the ValueCodec the queue was given, unencrypted unless one was.
CREATE TABLE toolgate_pending_call (
    approval_id      VARCHAR(191)  NOT NULL PRIMARY KEY,
    status           VARCHAR(16)   NOT NULL,
    call_id          VARCHAR(512)  NOT NULL,
    session_id       VARCHAR(512)  NOT NULL,
    agent_id         VARCHAR(512)  NOT NULL,
    principal_kind   VARCHAR(16)   NOT NULL,
    principal_id     VARCHAR(512)  NOT NULL,
    principal_tenant VARCHAR(512),
    principal_roles  MEDIUMTEXT    NOT NULL,
    tool             VARCHAR(1024) NOT NULL,
    call_at          BIGINT        NOT NULL,
    defer_rule       VARCHAR(512)  NOT NULL,
    payload          MEDIUMTEXT    NOT NULL,
    deferred_at      BIGINT        NOT NULL,
    expires_at       BIGINT        NOT NULL
);
CREATE INDEX toolgate_pending_call_status ON toolgate_pending_call (status, deferred_at);

-- ToolGate session store. One row per session: the state every check reads. The event log keeps
-- every session event, including the calls' REAL ARGUMENT VALUES, sealed by the store's ValueCodec
-- (unencrypted unless one was given), purge idle sessions (JdbcSessionStore.purgeIdle) to bound how long it is kept.
CREATE TABLE toolgate_session (
    session_id  VARCHAR(191)  NOT NULL PRIMARY KEY,
    version     BIGINT        NOT NULL,
    tool_calls  INT           NOT NULL,
    denials     INT           NOT NULL,
    taint       MEDIUMTEXT    NOT NULL,
    updated_at  BIGINT        NOT NULL
);
CREATE INDEX toolgate_session_updated ON toolgate_session (updated_at);
CREATE TABLE toolgate_session_event (
    session_id  VARCHAR(191)  NOT NULL,
    version     BIGINT        NOT NULL,
    event_type  VARCHAR(32)   NOT NULL,
    event       MEDIUMTEXT    NOT NULL,
    recorded_at BIGINT        NOT NULL,
    PRIMARY KEY (session_id, version)
);
