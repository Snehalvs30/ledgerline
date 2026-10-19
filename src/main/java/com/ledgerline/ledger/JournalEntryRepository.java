package com.ledgerline.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {
}