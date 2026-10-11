-- ToolGate approval queue, MySQL. Requires utf8mb4. Times are epoch microseconds (UTC), so no time zone or
-- timestamp type differs between databases. payload holds the call's real arguments and the deferral
-- reason, as JSON, sealed by the ValueCodec the queue was given, unencrypted unless one was, and so is outcome,
-- the executor's summary of how an approved call's run ended.
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
    expires_at       BIGINT        NOT NULL,
    version          BIGINT        DEFAULT 0 NOT NULL,
    executor         VARCHAR(512),
    claimed_at       BIGINT,
    lease_until      BIGINT,
    finished_at      BIGINT,
    outcome          MEDIUMTEXT
);
CREATE INDEX toolgate_pending_call_status ON toolgate_pending_call (status, deferred_at);
-- One row per decision on a queued call: who approved, rejected or cancelled it, under which access, and
-- why. comment is sealed by the queue's ValueCodec, like payload, since a reviewer may quote the call.
CREATE TABLE toolgate_pending_call_decision (
    approval_id VARCHAR(191)  NOT NULL,
    seq         INT           NOT NULL,
    kind        VARCHAR(16)   NOT NULL,
    decided_by  VARCHAR(512)  NOT NULL,
    access      VARCHAR(16)   NOT NULL,
    decided_at  BIGINT        NOT NULL,
    comment     MEDIUMTEXT,
    PRIMARY KEY (approval_id, seq)
);
-- Counts super admin decisions across instances for the burst alert.
CREATE INDEX toolgate_pending_call_decision_access ON toolgate_pending_call_decision (access, decided_at);

-- ToolGate session store. One row per session: the state every check reads. The event log keeps
-- every session event, including the calls' REAL ARGUMENT VALUES, sealed by the store's ValueCodec
-- (unencrypted unless one was given), purge idle sessions (JdbcSessionStore.purgeIdle) to bound how long it is kept.
CREATE TABLE toolgate_session (
    session_id  VARCHAR(191)  NOT NULL PRIMARY KEY,
    version     BIGINT        NOT NULL,
    tool_calls  INT           NOT NULL,
    denials     INT           NOT NULL,
    taint       MEDIUMTEXT    NOT NULL,
    totals      MEDIUMTEXT    NOT NULL,
    unresolved  MEDIUMTEXT    NOT NULL,
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
CREATE INDEX toolgate_session_event_recorded ON toolgate_session_event (event_type, recorded_at);

-- ToolGate's audit log as rows (JdbcAuditSink). event is the same JSON line the toolgate.audit log writes:
-- argument summaries, never argument values, but reasons and comments as written, which can quote them.
-- Times are epoch microseconds (UTC). Purge with JdbcAuditSink.purgeBefore.
CREATE TABLE toolgate_audit (
    event_id    VARCHAR(36)   NOT NULL PRIMARY KEY,
    recorded_at BIGINT        NOT NULL,
    kind        VARCHAR(32)   NOT NULL,
    call_id     VARCHAR(512),
    approval_id VARCHAR(191),
    session_id  VARCHAR(191),
    agent_id    VARCHAR(512),
    actor_id    VARCHAR(512),
    tenant      VARCHAR(512),
    tool        VARCHAR(1024),
    outcome     VARCHAR(32),
    rule_id     VARCHAR(512),
    mode        VARCHAR(16),
    policy_hash VARCHAR(128),
    event       MEDIUMTEXT        NOT NULL
);
CREATE INDEX toolgate_audit_recorded ON toolgate_audit (recorded_at);
CREATE INDEX toolgate_audit_kind ON toolgate_audit (kind, recorded_at);
CREATE INDEX toolgate_audit_session ON toolgate_audit (session_id, recorded_at);
CREATE INDEX toolgate_audit_approval ON toolgate_audit (approval_id);

-- Read views, version 1, for the application's own screens, reports and dashboards. Their columns stay
-- stable across ToolGate versions, a change comes as toolgate_v2_*. They leave out everything sealed or
-- able to quote argument values: payloads, outcomes, comments, reasons and session details.
CREATE VIEW toolgate_v1_approvals AS
    SELECT approval_id, status, call_id, session_id, agent_id, principal_kind, principal_id, principal_tenant,
           tool, defer_rule, deferred_at, expires_at, executor, claimed_at, finished_at
    FROM toolgate_pending_call;
CREATE VIEW toolgate_v1_approval_decisions AS
    SELECT approval_id, seq, kind, decided_by, access, decided_at
    FROM toolgate_pending_call_decision;
CREATE VIEW toolgate_v1_decisions AS
    SELECT event_id, recorded_at, call_id, session_id, agent_id, actor_id AS principal_id,
           tenant AS principal_tenant, tool, outcome, rule_id, mode, policy_hash
    FROM toolgate_audit WHERE kind = 'DECIDED';
CREATE VIEW toolgate_v1_sessions AS
    SELECT session_id, version, tool_calls, denials, updated_at
    FROM toolgate_session;
