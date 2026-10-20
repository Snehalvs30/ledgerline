package com.ledgerline.outbox;

import com.ledgerline.account.AccountResponse;
import com.ledgerline.support.IntegrationTest;
import com.ledgerline.transfer.TransferResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxIntegrationTest extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${ledgerline.outbox.topic}")
    private String topic;

    @Test
    void transferEventIsPublishedAndConsumed() throws Exception {
        TransferResponse transfer = makeTransfer(1_200);

        awaitUntil(TIMEOUT, () -> notificationCount(transfer.transferId()) == 2);

        Boolean published = jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id = ?",
                Boolean.class, transfer.transferId());
        assertThat(published).isTrue();

        List<String> messages = jdbc.queryForList("""
                SELECT n.message FROM notifications n
                JOIN outbox_events o ON o.id = n.event_id
                WHERE o.aggregate_id = ?
                """, String.class, transfer.transferId());
        assertThat(messages).containsExactlyInAnyOrder("You sent 12.00 USD", "You received 12.00 USD");
    }

    @Test
    void duplicateDeliveryDoesNotDuplicateNotifications() throws Exception {
        TransferResponse transfer = makeTransfer(800);
        awaitUntil(TIMEOUT, () -> notificationCount(transfer.transferId()) == 2);

        // Simulate Kafka delivering the same event again (as happens after a relay crash).
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE aggregate_id = ?",
                String.class, transfer.transferId());
        kafkaTemplate.send(topic, transfer.transferId().toString(), payload).get(10, TimeUnit.SECONDS);

        // Give the consumer time to receive (and skip) the duplicate.
        Thread.sleep(3_000);
        assertThat(notificationCount(transfer.transferId())).isEqualTo(2);
    }

    private TransferResponse makeTransfer(long amount) {
        AccountResponse alice = createAccount("Alice", "USD");
        AccountResponse bob = createAccount("Bob", "USD");
        deposit(alice.id(), 10_000);
        return read(transfer(alice.id(), bob.id(), amount), TransferResponse.class);
    }

    private long notificationCount(UUID transferId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications n
                JOIN outbox_events o ON o.id = n.event_id
                WHERE o.aggregate_id = ?
                """, Long.class, transferId);
        return count == null ? 0 : count;
    }
}