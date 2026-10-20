package com.ledgerline.transfer;

import com.ledgerline.account.AccountResponse;
import com.ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferApiTest extends IntegrationTest {

    @Test
    void movesMoneyAndWritesBalancedPostings() {
        AccountResponse alice = createAccount("Alice", "USD");
        AccountResponse bob = createAccount("Bob", "USD");
        deposit(alice.id(), 10_000);

        ResponseEntity<String> response = transfer(alice.id(), bob.id(), 3_000);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        TransferResponse transfer = read(response, TransferResponse.class);
        assertThat(transfer.amount()).isEqualTo(3_000);
        assertThat(transfer.currency()).isEqualTo("USD");
        assertThat(balanceOf(alice.id())).isEqualTo(7_000);
        assertThat(balanceOf(bob.id())).isEqualTo(3_000);

        // Double entry: exactly two postings, summing to zero.
        Long postingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM postings WHERE journal_entry_id = ?", Long.class, transfer.transferId());
        Long postingSum = jdbc.queryForObject(
                "SELECT SUM(amount) FROM postings WHERE journal_entry_id = ?", Long.class, transfer.transferId());
        assertThat(postingCount).isEqualTo(2);
        assertThat(postingSum).isZero();
    }

    @Test
    void rejectsOverdraftAndLeavesBalancesUntouched() {
        AccountResponse alice = createAccount("Alice", "USD");
        AccountResponse bob = createAccount("Bob", "USD");
        deposit(alice.id(), 1_000);

        ResponseEntity<String> response = transfer(alice.id(), bob.id(), 1_001);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(read(response, Problem.class).title()).isEqualTo("Insufficient funds");
        assertThat(balanceOf(alice.id())).isEqualTo(1_000);
        assertThat(balanceOf(bob.id())).isZero();
    }

    @Test
    void allowsSpendingTheExactBalance() {
        AccountResponse alice = createAccount("Alice", "USD");
        AccountResponse bob = createAccount("Bob", "USD");
        deposit(alice.id(), 1_000);

        assertThat(transfer(alice.id(), bob.id(), 1_000).getStatusCode().value()).isEqualTo(201);
        assertThat(balanceOf(alice.id())).isZero();
    }

    @Test
    void rejectsCurrencyMismatch() {
        AccountResponse alice = createAccount("Alice", "USD");
        AccountResponse eurAccount = createAccount("Euro Account", "EUR");
        deposit(alice.id(), 1_000);

        ResponseEntity<String> response = transfer(alice.id(), eurAccount.id(), 500);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(read(response, Problem.class).title()).isEqualTo("Invalid transfer");
    }

    @Test
    void rejectsTransferToSameAccount() {
        AccountResponse alice = createAccount("Alice", "USD");
        deposit(alice.id(), 1_000);

        assertThat(transfer(alice.id(), alice.id(), 100).getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        AccountResponse alice = createAccount("Alice", "USD");
        AccountResponse bob = createAccount("Bob", "USD");

        assertThat(transfer(alice.id(), bob.id(), 0).getStatusCode().value()).isEqualTo(400);
        assertThat(transfer(alice.id(), bob.id(), -500).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void returns404ForUnknownAccount() {
        AccountResponse alice = createAccount("Alice", "USD");
        deposit(alice.id(), 1_000);

        assertThat(transfer(alice.id(), UUID.randomUUID(), 100).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void requiresIdempotencyKeyHeader() {
        AccountResponse alice = createAccount("Alice", "USD");
        AccountResponse bob = createAccount("Bob", "USD");

        ResponseEntity<String> response =
                postTransfer(null, new TransferRequest(alice.id(), bob.id(), 100L, null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}