package com.ledgerline.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Move `amount` minor units (cents) from one account to another. */
public record TransferRequest(

        @NotNull
        UUID fromAccountId,

        @NotNull
        UUID toAccountId,

        @NotNull
        @Positive
        Long amount,

        @Size(max = 255)
        String description
) {
}