-- Inter-bank (item 9). The same migration runs on every bank's database.
-- Remote account ids are stored on the grant and the ledger effect, so those foreign keys go.
-- The cloned transactions table still requires two local accounts; each bank posts against its settlement account.

ALTER TABLE auth_grant DROP CONSTRAINT IF EXISTS auth_grant_payee_account_id_fkey;
ALTER TABLE ledger DROP CONSTRAINT IF EXISTS ledger_payer_account_id_fkey;
ALTER TABLE ledger DROP CONSTRAINT IF EXISTS ledger_payee_account_id_fkey;

CREATE TABLE institution (
    id                    VARCHAR(64) PRIMARY KEY,
    public_key_pem        TEXT        NOT NULL,
    settlement_account_id UUID        REFERENCES accounts (id)
);

CREATE TABLE transfer_correlation (
    end_to_end_id    VARCHAR(255) PRIMARY KEY,
    auth_ref         VARCHAR(255) NOT NULL,
    debit_event_id   VARCHAR(255),
    credit_event_id  VARCHAR(255),
    state            VARCHAR(16)  NOT NULL,
    attestation      TEXT,
    CONSTRAINT chk_transfer_correlation_state
        CHECK (state IN ('DEBITED', 'CREDITED', 'REJECTED', 'REVERSED'))
);

CREATE TABLE outbox (
    id           UUID         PRIMARY KEY,
    kind         VARCHAR(16)  NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    CONSTRAINT chk_outbox_kind CHECK (kind IN ('PACS008', 'PACS002'))
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
