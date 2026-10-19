package com.ledgerline.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What a client sends to create an account.
 * Deliberately has no balance or type field: clients can't choose either.
 */
public record CreateAccountRequest(

        @NotBlank
        @Size(max = 100)
        String name,

        @NotNull
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase currency code, e.g. USD")
        String currency
) {
}