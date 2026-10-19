-- V1: core ledger tables
-- Money is stored as BIGINT minor units (cents). $50.00 = 5000. Never floats.

-- An account holds a balance in one currency.
-- CUSTOMER accounts can never go negative.
-- SYSTEM accounts (e.g. "funding") may go negative: they are where money
-- enters or leaves the ledger, so every deposit is still a balanced transfer.
CREATE TABLE accounts (
                          id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                          name        VARCHAR(100) NOT NULL,
                          type        VARCHAR(20)  NOT NULL,
                          currency    CHAR(3)      NOT NULL,
                          balance     BIGINT       NOT NULL DEFAULT 0,
                          created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

                          CONSTRAINT chk_account_type      CHECK (type IN ('CUSTOMER', 'SYSTEM')),
                          CONSTRAINT chk_currency_format   CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_customer_balance  CHECK (type = 'SYSTEM' OR balance >= 0)
);

-- One journal entry per transfer. It groups the postings that belong together.
CREATE TABLE journal_entries (
                                 id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                 description  VARCHAR(255),
                                 created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A posting is one line of a journal entry: negative = money out, positive = money in.
-- The postings of each journal entry must sum to zero. That rule is enforced
-- in the transfer service and verified by the reconciliation check.
-- Postings are append-only: never updated or deleted.
CREATE TABLE postings (
                          id                BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                          journal_entry_id  UUID         NOT NULL REFERENCES journal_entries (id),
                          account_id        UUID         NOT NULL REFERENCES accounts (id),
                          amount            BIGINT       NOT NULL,
                          created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

                          CONSTRAINT chk_posting_nonzero CHECK (amount <> 0)
);

-- Account statement queries: "postings for this account, newest first".
CREATE INDEX idx_postings_account_created ON postings (account_id, created_at DESC, id DESC);

-- Look up all postings of a journal entry.
CREATE INDEX idx_postings_journal_entry ON postings (journal_entry_id);