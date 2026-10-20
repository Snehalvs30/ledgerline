package com.ledgerline.transfer;

import com.ledgerline.account.AccountResponse;
import com.ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyTest extends IntegrationTest {

    @Test
    void retryWithSameKeyReturnsOriginalTransferAndMovesMoneyOnce() {
        AccountResponse alice = createAccount("Alice", "USD");
        AccountResponse bob = createAccount("Bob", "USD");
        deposit(alice.id(), 10_000);
        String key = UUID.randomUUID().toString();
        TransferRequest request = new TransferRequest(alice.id(), bob.id(), 2_500L, "Rent share");

        ResponseEntity<String> first = postTransfer(key, request);
        ResponseEntity<String> retry = postTransfer(key, request);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(read(retry, TransferResponse.class)).isEqualTo(read(first, TransferResponse.class));
        assertThat(balanceOf(bob.id())).isEqualTo(2_500);   // not 5,000
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        AccountResponse alice = createAccount("Alice", "USD");
        AccountResponse bob = createAccount("Bob", "USD");
        deposit(alice.id(), 10_000);
        String key = UUID.randomUUID().toString();

        postTransfer(key, new TransferRequest(alice.id(), bob.id(), 1_000L, null));
        ResponseEntity<String> reused = postTransfer(key, new TransferRequest(alice.id(), bob.id(), 9_000L, null));

        assertThat(reused.getStatusCode().value()).isEqualTo(422);
        assertThat(read(reused, Problem.class).title()).isEqualTo("Idempotency key reused");
        assertThat(balanceOf(bob.id())).isEqualTo(1_000);
    }

    @Test
    void failedTransferDoesNotConsumeTheKey() {
        // Design decision (see ADR 0003): only successful transfers are remembered.
        AccountResponse alice = createAccount("Alice", "USD");
        AccountResponse bob = createAccount("Bob", "USD");
        String key = UUID.randomUUID().toString();
        TransferRequest request = new TransferRequest(alice.id(), bob.id(), 500L, null);

        assertThat(postTransfer(key, request).getStatusCode().value()).isEqualTo(422); // no funds yet
        deposit(alice.id(), 1_000);
        ResponseEntity<String> retry = postTransfer(key, request);

        assertThat(retry.getStatusCode().value()).isEqualTo(201);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
    }

    @Test
    void concurrentRequestsWithSameKeyMoveMoneyExactlyOnce() throws Exception {
        AccountResponse alice = createAccount("Alice", "USD");
        AccountResponse bob = createAccount("Bob", "USD");
        deposit(alice.id(), 10_000);
        String key = UUID.randomUUID().toString();
        TransferRequest request = new TransferRequest(alice.id(), bob.id(), 700L, null);
        int clients = 10;

        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            futures.add(pool.submit(() -> {
                startGun.await();          // all threads fire at the same moment
                return postTransfer(key, request);
            }));
        }
        startGun.countDown();

        List<ResponseEntity<String>> responses = new ArrayList<>();
        for (Future<ResponseEntity<String>> future : futures) {
            responses.add(future.get());
        }
        pool.shutdown();

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(201));
        long originals = responses.stream()
                .filter(r -> "false".equals(r.getHeaders().getFirst("Idempotent-Replayed")))
                .count();
        assertThat(originals).isEqualTo(1);
        assertThat(responses.stream().map(r -> read(r, TransferResponse.class).transferId()).distinct())
                .hasSize(1);
        assertThat(balanceOf(bob.id())).isEqualTo(700);
    }
}