-- V5: who owns each account, so users can only see and spend from their own accounts.
-- The value is the "sub" (subject) claim of the JWT that created the account.
-- NULL for SYSTEM accounts and for accounts created before authentication existed;
-- only ledger administrators can use those.
ALTER TABLE accounts ADD COLUMN owner_id VARCHAR(100);

CREATE INDEX idx_accounts_owner ON accounts (owner_id);