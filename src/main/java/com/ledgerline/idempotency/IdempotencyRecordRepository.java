package com.ledgerline.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    /**
     * Claims the key. Returns 1 if this request is the first to use it, 0 if the key already exists.
     *
     * If another transaction has inserted the same key but not committed yet, PostgreSQL makes
     * this statement WAIT until that transaction finishes:
     *  - it commits:     this returns 0, and we replay its result
     *  - it rolls back:  this insert goes through and returns 1, and we do the transfer
     * So two concurrent requests with the same key can never both move money.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (idempotency_key, request_hash, created_at)
            VALUES (:key, :requestHash, now())
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int tryInsert(@Param("key") String key, @Param("requestHash") String requestHash);

    @Modifying
    @Query(value = "UPDATE idempotency_keys SET transfer_id = :transferId WHERE idempotency_key = :key",
            nativeQuery = true)
    int attachTransfer(@Param("key") String key, @Param("transferId") UUID transferId);
}