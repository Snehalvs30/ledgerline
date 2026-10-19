package com.ledgerline.account;

/**
 * CUSTOMER accounts belong to users and can never go negative.
 * SYSTEM accounts (e.g. "funding") represent money entering or leaving the ledger
 * and are allowed to go negative.
 */
public enum AccountType {
    CUSTOMER,
    SYSTEM
}