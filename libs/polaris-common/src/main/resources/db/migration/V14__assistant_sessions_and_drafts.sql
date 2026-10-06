-- V14__assistant_sessions_and_drafts.sql
-- Delivers the assistant session / order draft schema from ADR-0004 §3.C. V6 already ran with
-- these tables commented out, so it stays unchanged (forward-only) and this migration adds them.
--
-- Portability: the same scripts run on H2 (default local profile of both apps) and PostgreSQL.
-- H2 has no JSONB and no partial indexes, so draft items use the standard SQL JSON type (still
-- validated as JSON by both databases), and "one open draft per session" is enforced with a
-- plain UNIQUE column that is only populated while the draft is open (NULLs never collide).

CREATE TABLE assistant_sessions (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    customer_id BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_assistant_sessions_user ON assistant_sessions(user_id, status);

-- Nullable: rows written before V14 belong to no session (history used to live in memory).
ALTER TABLE assistant_messages ADD COLUMN session_id VARCHAR(64);
ALTER TABLE assistant_messages ADD CONSTRAINT fk_assistant_messages_session
    FOREIGN KEY (session_id) REFERENCES assistant_sessions(id) ON DELETE CASCADE;
-- History is read in append order (identity id), so index (session_id, id) rather than created_at.
CREATE INDEX idx_assistant_messages_session ON assistant_messages(session_id, id);

CREATE TABLE assistant_order_drafts (
    id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL REFERENCES assistant_sessions(id) ON DELETE CASCADE,
    customer_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'WAITING_CONFIRMATION',
    items JSON NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_order_number VARCHAR(64),
    idempotency_key VARCHAR(100),
    -- Equals session_id while the draft is WAITING_CONFIRMATION, NULL otherwise.
    open_session_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_assistant_drafts_status
        CHECK (status IN ('WAITING_CONFIRMATION', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'INVALIDATED')),
    CONSTRAINT chk_assistant_drafts_open_session
        -- IS NOT NULL is required: a NULL comparison would make the first branch UNKNOWN, which a CHECK accepts.
        CHECK ((status = 'WAITING_CONFIRMATION' AND open_session_id IS NOT NULL AND open_session_id = session_id)
            OR (status <> 'WAITING_CONFIRMATION' AND open_session_id IS NULL)),
    CONSTRAINT uq_assistant_drafts_open_session UNIQUE (open_session_id)
);
CREATE INDEX idx_assistant_drafts_session ON assistant_order_drafts(session_id, status);
CREATE INDEX idx_assistant_drafts_expiry ON assistant_order_drafts(status, expires_at);
