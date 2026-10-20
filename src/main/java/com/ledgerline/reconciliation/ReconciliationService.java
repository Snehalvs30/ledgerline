package com.ledgerline.reconciliation;

import com.ledgerline.reconciliation.ReconciliationReport.AccountMismatch;
import com.ledgerline.reconciliation.ReconciliationReport.UnbalancedEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Proves the books balance, using plain SQL aggregates (JdbcTemplate), which are
 * clearer and faster than loading entities for this kind of check.
 */
@Service
public class ReconciliationService {

    private static final String UNBALANCED_ENTRIES_SQL = """
            SELECT journal_entry_id, SUM(amount)::bigint AS total
            FROM postings
            GROUP BY journal_entry_id
            HAVING SUM(amount) <> 0
            """;

    private static final String ACCOUNT_MISMATCHES_SQL = """
            SELECT a.id, a.balance, COALESCE(p.total, 0)::bigint AS posted
            FROM accounts a
            LEFT JOIN (
                SELECT account_id, SUM(amount) AS total
                FROM postings
                GROUP BY account_id
            ) p ON p.account_id = a.id
            WHERE a.balance <> COALESCE(p.total, 0)
            """;

    private static final String TOTALS_BY_CURRENCY_SQL = """
            SELECT currency, SUM(balance)::bigint AS total
            FROM accounts
            GROUP BY currency
            ORDER BY currency
            """;

    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * REPEATABLE READ: all three queries see the SAME snapshot of the database.
     * Without it, a transfer committing between two queries could make healthy
     * data look inconsistent and raise a false alarm.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport run() {
        List<UnbalancedEntry> unbalanced = jdbc.query(UNBALANCED_ENTRIES_SQL, (rs, row) ->
                new UnbalancedEntry(rs.getObject("journal_entry_id", UUID.class), rs.getLong("total")));

        List<AccountMismatch> mismatches = jdbc.query(ACCOUNT_MISMATCHES_SQL, (rs, row) ->
                new AccountMismatch(rs.getObject("id", UUID.class), rs.getLong("balance"), rs.getLong("posted")));

        Map<String, Long> totals = jdbc.query(TOTALS_BY_CURRENCY_SQL, (rs, row) ->
                        Map.entry(rs.getString("currency").trim(), rs.getLong("total")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        long nonZeroCurrencies = totals.values().stream().filter(total -> total != 0).count();
        int violations = unbalanced.size() + mismatches.size() + (int) nonZeroCurrencies;

        return new ReconciliationReport(violations == 0, violations, Instant.now(),
                totals, unbalanced, mismatches);
    }
}