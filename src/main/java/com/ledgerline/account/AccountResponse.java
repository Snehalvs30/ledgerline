package com.ledgerline.account;

import java.time.Instant;
import java.util.UUID;

/**
 * What the API returns. Keeps the database entity private, so the
 * table can change without breaking clients.
 */
public record AccountResponse(
        UUID id,
        String name,
        AccountType type,
        String currency,
        long balance,          // minor units, e.g. 5000 = 50.00
        Instant createdAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getCurrency(),
                account.getBalance(),
                account.getCreatedAt()
        );
    }
}