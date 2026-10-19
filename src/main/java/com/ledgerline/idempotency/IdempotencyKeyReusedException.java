package com.ledgerline.idempotency;

/** The same Idempotency-Key was sent again, but with a different request body. */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request");
    }
}