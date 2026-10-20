package com.ledgerline.notification;

import com.ledgerline.transfer.TransferCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Reads TransferCompleted events and writes a notification for each side of the transfer.
 *
 * Kafka can deliver the same event more than once, so this consumer is IDEMPOTENT:
 * recording the event ID and writing the notifications happen in one transaction.
 * A duplicate fails to claim the event ID and is skipped. The result is an
 * exactly-once EFFECT on top of at-least-once delivery.
 */
@Component
public class TransferNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransferNotificationConsumer.class);
    private static final String CONSUMER_NAME = "transfer-notifications";

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public TransferNotificationConsumer(JdbcTemplate jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = "${ledgerline.outbox.topic}", groupId = "ledgerline-notifications")
    @Transactional
    public void onTransferCompleted(String payload) {
        TransferCompletedEvent event = jsonMapper.readValue(payload, TransferCompletedEvent.class);

        int claimed = jdbc.update("""
                INSERT INTO processed_events (consumer, event_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, CONSUMER_NAME, event.eventId());

        if (claimed == 0) {
            log.info("Duplicate event {} skipped", event.eventId());
            return;
        }

        String amount = formatAmount(event.amount(), event.currency());
        insertNotification(event.fromAccountId(), event.eventId(), "You sent " + amount);
        insertNotification(event.toAccountId(), event.eventId(), "You received " + amount);
        log.info("Notified both sides of transfer {}", event.transferId());
    }

    private void insertNotification(UUID accountId, UUID eventId, String message) {
        jdbc.update("INSERT INTO notifications (account_id, event_id, message) VALUES (?, ?, ?)",
                accountId, eventId, message);
    }

    /** 3000 -> "30.00 USD". Assumes 2 decimal places, which is true for USD. */
    private static String formatAmount(long minorUnits, String currency) {
        return BigDecimal.valueOf(minorUnits, 2).toPlainString() + " " + currency;
    }
}