package com.ledgerline.transfer;

import com.ledgerline.account.Account;
import com.ledgerline.account.AccountNotFoundException;
import com.ledgerline.account.AccountRepository;
import com.ledgerline.idempotency.IdempotencyKeyReusedException;
import com.ledgerline.idempotency.IdempotencyRecord;
import com.ledgerline.idempotency.IdempotencyRecordRepository;
import com.ledgerline.idempotency.InvalidIdempotencyKeyException;
import com.ledgerline.ledger.JournalEntry;
import com.ledgerline.ledger.JournalEntryRepository;
import com.ledgerline.ledger.Posting;
import com.ledgerline.ledger.PostingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TransferService {

    private static final int MAX_KEY_LENGTH = 255;

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final PostingRepository postingRepository;
    private final IdempotencyRecordRepository idempotencyRepository;

    public TransferService(AccountRepository accountRepository,
                           JournalEntryRepository journalEntryRepository,
                           PostingRepository postingRepository,
                           IdempotencyRecordRepository idempotencyRepository) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.postingRepository = postingRepository;
        this.idempotencyRepository = idempotencyRepository;
    }

    /**
     * Runs in ONE transaction: claiming the key, moving the money and recording the
     * result are saved together, or not at all.
     *
     * If the transfer fails (e.g. insufficient funds), the whole transaction rolls back,
     * including the key. A retry with the same key is then evaluated again from scratch.
     * We only remember successful transfers.
     */
    @Transactional
    public TransferResult transfer(String idempotencyKey, TransferRequest request) {
        validateKey(idempotencyKey);
        String fingerprint = request.fingerprint();

        boolean firstTime = idempotencyRepository.tryInsert(idempotencyKey, fingerprint) == 1;
        if (!firstTime) {
            return replay(idempotencyKey, fingerprint);
        }

        TransferResponse response = executeTransfer(request);
        idempotencyRepository.attachTransfer(idempotencyKey, response.transferId());
        return new TransferResult(response, false);
    }

    private TransferResult replay(String key, String fingerprint) {
        // Under READ COMMITTED each statement sees the latest committed data, so this
        // finds the row even if another transaction committed it a moment ago.
        IdempotencyRecord record = idempotencyRepository.findById(key)
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished: " + key));

        if (!record.getRequestHash().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException(key);
        }
        if (record.getTransferId() == null) {
            // Can't happen: the key and its transfer are committed in the same transaction.
            throw new IllegalStateException("Idempotency key " + key + " has no transfer");
        }
        return new TransferResult(loadTransfer(record.getTransferId()), true);
    }

    private TransferResponse executeTransfer(TransferRequest request) {
        UUID fromId = request.fromAccountId();
        UUID toId = request.toAccountId();
        long amount = request.amount();

        if (fromId.equals(toId)) {
            throw new InvalidTransferException("Cannot transfer to the same account");
        }

        // 1. Lock both account rows, always in ID order (prevents deadlocks).
        Map<UUID, Account> locked = accountRepository.lockAllByIdOrdered(List.of(fromId, toId))
                .stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));

        Account from = locked.get(fromId);
        Account to = locked.get(toId);
        if (from == null) throw new AccountNotFoundException(fromId);
        if (to == null) throw new AccountNotFoundException(toId);

        // 2. Business rules, checked while holding the locks so nothing changes underneath us.
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new InvalidTransferException(
                    "Currency mismatch: " + from.getCurrency() + " to " + to.getCurrency());
        }

        // 3. Move the money. debit() rejects overdrafts on CUSTOMER accounts.
        from.debit(amount);
        to.credit(amount);

        // 4. Record it with double entry: two postings that sum to zero.
        JournalEntry entry = journalEntryRepository.save(new JournalEntry(request.description()));
        postingRepository.saveAll(List.of(
                new Posting(entry.getId(), fromId, -amount),
                new Posting(entry.getId(), toId, amount)
        ));

        return new TransferResponse(entry.getId(), fromId, toId, amount,
                from.getCurrency(), entry.getDescription(), entry.getCreatedAt());
    }

    /** Rebuilds a transfer's response from the ledger, for replays. */
    private TransferResponse loadTransfer(UUID transferId) {
        JournalEntry entry = journalEntryRepository.findById(transferId)
                .orElseThrow(() -> new IllegalStateException("Missing journal entry " + transferId));
        List<Posting> postings = postingRepository.findByJournalEntryIdOrderByAmountAsc(transferId);
        Posting debit = postings.get(0);   // negative amount: the "from" side
        Posting credit = postings.get(1);  // positive amount: the "to" side
        String currency = accountRepository.findById(debit.getAccountId())
                .orElseThrow(() -> new AccountNotFoundException(debit.getAccountId()))
                .getCurrency();

        return new TransferResponse(entry.getId(), debit.getAccountId(), credit.getAccountId(),
                credit.getAmount(), currency, entry.getDescription(), entry.getCreatedAt());
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new InvalidIdempotencyKeyException("Idempotency-Key header must not be blank");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }
    }
}