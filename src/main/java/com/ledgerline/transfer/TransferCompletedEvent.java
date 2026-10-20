package com.ledgerline.transfer;

import java.time.Instant;
import java.util.UUID;

/** Published to Kafka (via the outbox) after a transfer commits. */
public record TransferCompletedEvent(
        UUID eventId,
        String eventType,
        UUID transferId,
        UUID fromAccountId,
        UUID toAccountId,
        long amount,
        String currency,
        String description,
        Instant occurredAt
) {
    public static final String TYPE = "TransferCompleted";
}