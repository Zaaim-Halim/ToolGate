-- ToolGate approval queue, PostgreSQL. Times are epoch microseconds (UTC), so no time zone or
-- timestamp type differs between databases. payload holds the call's real arguments and the deferral
-- reason, as JSON, sealed by the ValueCodec the queue was given, unencrypted unless one was, and so is outcome,
-- the executor's summary of how an approved call's run ended.
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
    call_at          BIGINT        NOT NULL,
    defer_rule       VARCHAR(512)  NOT NULL,
    payload          TEXT          NOT NULL,
    deferred_at      BIGINT        NOT NULL,
    expires_at       BIGINT        NOT NULL,
    version          BIGINT        DEFAULT 0 NOT NULL,
    executor         VARCHAR(512),
    claimed_at       BIGINT,
    lease_until      BIGINT,
    finished_at      BIGINT,
    outcome          TEXT
);
CREATE INDEX toolgate_pending_call_status ON toolgate_pending_call (status, deferred_at);
-- One row per decision on a queued call: who approved, rejected or cancelled it, under which access, and
-- why. comment is sealed by the queue's ValueCodec, like payload, since a reviewer may quote the call.
CREATE TABLE toolgate_pending_call_decision (
    approval_id VARCHAR(512)  NOT NULL,
    seq         INT           NOT NULL,
    kind        VARCHAR(16)   NOT NULL,
    decided_by  VARCHAR(512)  NOT NULL,
    access      VARCHAR(16)   NOT NULL,
    decided_at  BIGINT        NOT NULL,
    comment     TEXT,
    PRIMARY KEY (approval_id, seq)
);

-- ToolGate session store. One row per session: the state every check reads. The event log keeps
-- every session event, including the calls' REAL ARGUMENT VALUES, sealed by the store's ValueCodec
-- (unencrypted unless one was given), purge idle sessions (JdbcSessionStore.purgeIdle) to bound how long it is kept.
CREATE TABLE toolgate_session (
    session_id  VARCHAR(512)  NOT NULL PRIMARY KEY,
    version     BIGINT        NOT NULL,
    tool_calls  INT           NOT NULL,
    denials     INT           NOT NULL,
    taint       TEXT          NOT NULL,
    totals      TEXT          NOT NULL,
    unresolved  TEXT          NOT NULL,
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
CREATE INDEX toolgate_session_event_recorded ON toolgate_session_event (event_type, recorded_at);
