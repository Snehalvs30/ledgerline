package com.ledgerline.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Remembers which transfer an Idempotency-Key produced, and a fingerprint of the
 * request it was used with. Rows are written with native SQL (see the repository),
 * so this entity is only used for reading.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", length = 255)
    private String key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    public String getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public UUID getTransferId() { return transferId; }
    public Instant getCreatedAt() { return createdAt; }
}