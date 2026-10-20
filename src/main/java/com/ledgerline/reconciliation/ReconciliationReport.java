package com.ledgerline.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The result of checking the ledger's invariants:
 *  1. Every journal entry's postings sum to zero.
 *  2. Every account's stored balance equals the sum of its postings.
 *  3. All balances in each currency sum to zero.
 */
public record ReconciliationReport(
        boolean balanced,
        int violations,
        Instant checkedAt,
        Map<String, Long> totalBalanceByCurrency,
        List<UnbalancedEntry> unbalancedEntries,
        List<AccountMismatch> accountMismatches
) {

    /** A journal entry whose postings don't sum to zero: money was created or destroyed. */
    public record UnbalancedEntry(UUID journalEntryId, long postingsTotal) {
    }

    /** An account whose stored balance disagrees with its posting history. */
    public record AccountMismatch(UUID accountId, long storedBalance, long postingsTotal) {
    }
}