package com.ledgerline.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    /**
     * SHA-256 fingerprint of the request. Stored with the Idempotency-Key so we can
     * detect a key being reused for a DIFFERENT request.
     */
    public String fingerprint() {
        String canonical = fromAccountId + "|" + toAccountId + "|" + amount + "|"
                + (description == null ? "" : description);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}