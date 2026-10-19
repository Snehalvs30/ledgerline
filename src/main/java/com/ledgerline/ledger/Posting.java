package com.ledgerline.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of a journal entry. Negative amount = money out, positive = money in.
 * Append-only: no setters, never updated or deleted.
 *
 * Stores plain IDs instead of @ManyToOne relationships on purpose: postings are
 * written once and read in bulk, so lazy-loading relationships add complexity
 * without benefit.
 */
@Entity
@Table(name = "postings")
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    private UUID journalEntryId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Posting() {
    }

    public Posting(UUID journalEntryId, UUID accountId, long amount) {
        this.journalEntryId = journalEntryId;
        this.accountId = accountId;
        this.amount = amount;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getJournalEntryId() { return journalEntryId; }
    public UUID getAccountId() { return accountId; }
    public long getAmount() { return amount; }
    public Instant getCreatedAt() { return createdAt; }
}