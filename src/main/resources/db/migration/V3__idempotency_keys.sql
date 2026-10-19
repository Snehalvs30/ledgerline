-- V3: idempotency keys, so a retried transfer request never moves money twice.
-- The PRIMARY KEY on idempotency_key is what makes this safe under concurrency:
-- PostgreSQL allows only one row per key, even if two requests arrive at once.
CREATE TABLE idempotency_keys (
                                  idempotency_key  VARCHAR(255)  PRIMARY KEY,
                                  request_hash     VARCHAR(64)   NOT NULL,   -- SHA-256 of the request body, hex
                                  transfer_id      UUID          REFERENCES journal_entries (id),
                                  created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);