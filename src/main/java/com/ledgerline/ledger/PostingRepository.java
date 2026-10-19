package com.ledgerline.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PostingRepository extends JpaRepository<Posting, Long> {

    /** The postings of one transfer, debit (negative) first. */
    List<Posting> findByJournalEntryIdOrderByAmountAsc(UUID journalEntryId);
}