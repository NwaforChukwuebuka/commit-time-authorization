-- Commit-time authorization, beside the cloned accounts / transactions / ledger_entries tables.
-- `ledger` is one effect row per committed event. `ledger_entries` remains the double-entry pair.

ALTER TABLE accounts
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN'));

CREATE TABLE auth_grant (
    auth_ref              VARCHAR(255)   PRIMARY KEY,
    payer_account_id      UUID           NOT NULL REFERENCES accounts (id),
    payee_account_id      UUID           NOT NULL REFERENCES accounts (id),
    amount                NUMERIC(19, 4) NOT NULL,
    currency              VARCHAR(3)     NOT NULL,
    status                VARCHAR(16)    NOT NULL,
    payload_hash          VARCHAR(64)    NOT NULL,
    consumed_by_event_id  VARCHAR(255),
    CONSTRAINT chk_auth_grant_status CHECK (status IN ('OPEN', 'CONSUMED', 'REVOKED')),
    CONSTRAINT chk_auth_grant_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_auth_grant_distinct_accounts CHECK (payer_account_id <> payee_account_id),
    CONSTRAINT chk_auth_grant_currency_format CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE decision (
    id          UUID           PRIMARY KEY,
    event_id    VARCHAR(255)   NOT NULL,
    auth_ref    VARCHAR(255)   NOT NULL,
    outcome     VARCHAR(16)    NOT NULL,
    reason      VARCHAR(64)    NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_decision_outcome CHECK (outcome IN ('COMMITTED', 'DUPLICATE', 'REJECTED', 'DECLINED'))
);

CREATE INDEX idx_decision_event_id ON decision (event_id);

CREATE TABLE ledger (
    id                UUID           PRIMARY KEY,
    event_id          VARCHAR(255)   NOT NULL,
    auth_ref          VARCHAR(255)   NOT NULL,
    payer_account_id  UUID           NOT NULL REFERENCES accounts (id),
    payee_account_id  UUID           NOT NULL REFERENCES accounts (id),
    amount            NUMERIC(19, 4) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    kind              VARCHAR(64)    NOT NULL,
    transaction_id    UUID           NOT NULL REFERENCES transactions (id),
    CONSTRAINT uq_ledger_event_id UNIQUE (event_id)
);
