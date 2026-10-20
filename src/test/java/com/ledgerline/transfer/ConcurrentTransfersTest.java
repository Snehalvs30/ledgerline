package com.ledgerline.transfer;

import com.ledgerline.account.AccountResponse;
import com.ledgerline.reconciliation.ReconciliationReport;
import com.ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headline test. Hundreds of random transfers between a handful of accounts,
 * fired in parallel, in both directions. If locking were wrong we'd see money
 * created or destroyed, negative balances, or deadlocks (which surface as 500s).
 */
class ConcurrentTransfersTest extends IntegrationTest {

    private static final int ACCOUNTS = 5;
    private static final long STARTING_BALANCE = 10_000;
    private static final int TRANSFERS = 500;
    private static final int THREADS = 16;

    @Test
    void concurrentTransfersNeverCreateOrDestroyMoney() throws Exception {
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            AccountResponse account = createAccount("Account " + i, "USD");
            deposit(account.id(), STARTING_BALANCE);
            accounts.add(account.id());
        }
        long totalBefore = totalBalance(accounts);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            statuses.add(pool.submit(() -> {
                startGun.await();
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int from = random.nextInt(ACCOUNTS);
                int to;
                do {
                    to = random.nextInt(ACCOUNTS);
                } while (to == from);
                long amount = 1 + random.nextInt(3_000);
                return transfer(accounts.get(from), accounts.get(to), amount).getStatusCode().value();
            }));
        }
        startGun.countDown();

        List<Integer> results = new ArrayList<>();
        for (Future<Integer> status : statuses) {
            results.add(status.get(2, TimeUnit.MINUTES));
        }
        pool.shutdown();
        Map<Integer, Long> countsByStatus = results.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        System.out.println("Status counts: " + countsByStatus);

        // Only "succeeded" or "insufficient funds". No 500s means no deadlocks or crashes.
        assertThat(countsByStatus.keySet()).isSubsetOf(201, 422);
        assertThat(countsByStatus.getOrDefault(201, 0L)).isPositive();

        // Money only moved between these accounts, so their total can't change.
        assertThat(totalBalance(accounts)).isEqualTo(totalBefore);

        // Customer accounts never went negative.
        for (UUID account : accounts) {
            assertThat(balanceOf(account)).isNotNegative();
        }

        // Every successful transfer wrote exactly one debit posting from these accounts.
        long debitPostings = 0;
        for (UUID account : accounts) {
            debitPostings += jdbc.queryForObject(
                    "SELECT COUNT(*) FROM postings WHERE account_id = ? AND amount < 0", Long.class, account);
        }
        assertThat(debitPostings).isEqualTo(countsByStatus.getOrDefault(201, 0L));

        // And the whole ledger still reconciles.
        ReconciliationReport report = reconcile();
        assertThat(report.balanced()).as("reconciliation report: %s", report).isTrue();
    }

    private long totalBalance(List<UUID> accounts) {
        return accounts.stream().mapToLong(this::balanceOf).sum();
    }
}