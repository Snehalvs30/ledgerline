package com.ledgerline.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One journal entry per transfer. Groups the postings that belong together. */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected JournalEntry() {
    }

    public JournalEntry(String description) {
        this.description = description;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
}