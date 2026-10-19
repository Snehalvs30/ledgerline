package com.ledgerline.transfer;

import java.time.Instant;
import java.util.UUID;

/** The transfer's ID is its journal entry's ID. */
public record TransferResponse(
        UUID transferId,
        UUID fromAccountId,
        UUID toAccountId,
        long amount,
        String currency,
        String description,
        Instant createdAt
) {
}