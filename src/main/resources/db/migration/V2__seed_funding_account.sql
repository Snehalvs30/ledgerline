-- V2: a SYSTEM "funding" account, the entry point for money into the ledger.
-- A deposit is just a transfer FROM this account TO a customer, so every
-- movement of money stays a balanced double-entry transfer.
-- Its balance goes negative and equals minus all the money issued, so the
-- sum of ALL account balances is always zero.
INSERT INTO accounts (id, name, type, currency, balance)
VALUES ('00000000-0000-0000-0000-000000000001', 'USD Funding', 'SYSTEM', 'USD', 0);