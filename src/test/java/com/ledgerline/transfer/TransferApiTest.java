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

    // ---- Authorization -----------------------------------------------------------

    @Test
    void cannotSpendFromSomeoneElsesAccount() {
        AccountResponse victim = createAccount("Victim", "USD");
        deposit(victim.id(), 5_000);
        String attackerToken = tokenFor("attacker-" + UUID.randomUUID(), USER_SCOPES);
        AccountResponse attackersAccount = createAccountAs(attackerToken, "Attacker", "USD");

        ResponseEntity<String> response = postTransferAs(attackerToken, UUID.randomUUID().toString(),
                new TransferRequest(victim.id(), attackersAccount.id(), 5_000L, null));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(balanceOf(victim.id())).isEqualTo(5_000);
        assertThat(balanceOf(attackersAccount.id())).isZero();
    }

    @Test
    void canPayIntoSomeoneElsesAccount() {
        AccountResponse mine = createAccount("Mine", "USD");
        deposit(mine.id(), 2_000);
        String friendsToken = tokenFor("friend-" + UUID.randomUUID(), USER_SCOPES);
        AccountResponse friendsAccount = createAccountAs(friendsToken, "Friend", "USD");

        assertThat(transfer(mine.id(), friendsAccount.id(), 500).getStatusCode().value()).isEqualTo(201);
        assertThat(balanceOf(friendsAccount.id())).isEqualTo(500);
    }

    @Test
    void customersCannotMintMoneyFromTheFundingAccount() {
        AccountResponse mine = createAccount("Mine", "USD");

        ResponseEntity<String> response = postTransfer(UUID.randomUUID().toString(),
                new TransferRequest(FUNDING_ACCOUNT_ID, mine.id(), 1_000_000L, null));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(balanceOf(mine.id())).isZero();
    }

    @Test
    void idempotencyKeysAreScopedPerCaller() {
        // Two different users pick the same key: each gets their own transfer.
        String sharedKey = "shared-key-" + UUID.randomUUID();
        AccountResponse aliceFrom = createAccount("Alice", "USD");
        AccountResponse aliceTo = createAccount("Alice savings", "USD");
        deposit(aliceFrom.id(), 1_000);
        String bobToken = tokenFor("bob-" + UUID.randomUUID(), USER_SCOPES);
        AccountResponse bobFrom = createAccountAs(bobToken, "Bob", "USD");
        AccountResponse bobTo = createAccountAs(bobToken, "Bob savings", "USD");
        deposit(bobFrom.id(), 1_000);

        ResponseEntity<String> alice = postTransfer(sharedKey,
                new TransferRequest(aliceFrom.id(), aliceTo.id(), 100L, null));
        ResponseEntity<String> bob = postTransferAs(bobToken, sharedKey,
                new TransferRequest(bobFrom.id(), bobTo.id(), 200L, null));

        assertThat(alice.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(bob.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(balanceOf(bobTo.id())).isEqualTo(200);
    }
}