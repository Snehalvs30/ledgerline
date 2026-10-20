package com.ledgerline.transfer;

import com.ledgerline.account.Account;
import com.ledgerline.account.AccountNotFoundException;
import com.ledgerline.account.AccountRepository;
import com.ledgerline.account.AccountType;
import com.ledgerline.idempotency.IdempotencyKeyReusedException;
import com.ledgerline.idempotency.IdempotencyRecord;
import com.ledgerline.idempotency.IdempotencyRecordRepository;
import com.ledgerline.idempotency.InvalidIdempotencyKeyException;
import com.ledgerline.ledger.JournalEntry;
import com.ledgerline.ledger.JournalEntryRepository;
import com.ledgerline.ledger.Posting;
import com.ledgerline.ledger.PostingRepository;
import com.ledgerline.outbox.OutboxWriter;
import com.ledgerline.security.Caller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TransferService {

    // Keys are stored as "<subject>:<key>" in a 255-character column; the subject is at most 100.
    private static final int MAX_KEY_LENGTH = 128;

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final PostingRepository postingRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final OutboxWriter outboxWriter;

    public TransferService(AccountRepository accountRepository,
                           JournalEntryRepository journalEntryRepository,
                           PostingRepository postingRepository,
                           IdempotencyRecordRepository idempotencyRepository,
                           OutboxWriter outboxWriter) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.postingRepository = postingRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.outboxWriter = outboxWriter;
    }

    /**
     * Runs in ONE transaction: claiming the key, moving the money, recording the
     * result and queueing the event are saved together, or not at all.
     *
     * If the transfer fails (e.g. insufficient funds), the whole transaction rolls back,
     * including the key. A retry with the same key is then evaluated again from scratch.
     * We only remember successful transfers.
     */
    @Transactional
    public TransferResult transfer(String idempotencyKey, TransferRequest request, Caller caller) {
        validateKey(idempotencyKey);
        // Keys are per caller: two clients that happen to pick the same key never collide,
        // and nobody can replay (or probe) another client's transfers by guessing keys.
        String scopedKey = caller.subject() + ":" + idempotencyKey;
        String fingerprint = request.fingerprint();

        boolean firstTime = idempotencyRepository.tryInsert(scopedKey, fingerprint) == 1;
        if (!firstTime) {
            return replay(scopedKey, fingerprint);
        }

        TransferResponse response = executeTransfer(request, caller);
        idempotencyRepository.attachTransfer(scopedKey, response.transferId());
        queueEvent(response);
        return new TransferResult(response, false);
    }

    private TransferResult replay(String key, String fingerprint) {
        // Under READ COMMITTED each statement sees the latest committed data, so this
        // finds the row even if another transaction committed it a moment ago.
        IdempotencyRecord record = idempotencyRepository.findById(key)
                .orElseThrow(() -> new IllegalStateException("Idempotency key vanished: " + key));

        if (!record.getRequestHash().equals(fingerprint)) {
            throw new IdempotencyKeyReusedException(key.substring(key.indexOf(':') + 1));
        }
        if (record.getTransferId() == null) {
            // Can't happen: the key and its transfer are committed in the same transaction.
            throw new IllegalStateException("Idempotency key " + key + " has no transfer");
        }
        // No new event on replay: the original transfer already queued one.
        return new TransferResult(loadTransfer(record.getTransferId()), true);
    }

    private TransferResponse executeTransfer(TransferRequest request, Caller caller) {
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

        // 2. Authorization: may this caller spend from this account? (Paying INTO
        //    anyone's account is allowed, like sending money to a friend.)
        authorizeDebit(from, caller);

        // 3. Business rules, checked while holding the locks so nothing changes underneath us.
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new InvalidTransferException(
                    "Currency mismatch: " + from.getCurrency() + " to " + to.getCurrency());
        }

        // 4. Move the money. debit() rejects overdrafts on CUSTOMER accounts.
        from.debit(amount);
        to.credit(amount);

        // 5. Record it with double entry: two postings that sum to zero.
        JournalEntry entry = journalEntryRepository.save(new JournalEntry(request.description()));
        postingRepository.saveAll(List.of(
                new Posting(entry.getId(), fromId, -amount),
                new Posting(entry.getId(), toId, amount)
        ));

        return new TransferResponse(entry.getId(), fromId, toId, amount,
                from.getCurrency(), entry.getDescription(), entry.getCreatedAt());
    }

    /**
     * Admins may move money from any account (deposits, corrections).
     * Everyone else may only spend from CUSTOMER accounts they own.
     */
    private static void authorizeDebit(Account from, Caller caller) {
        if (caller.admin()) {
            return;
        }
        if (from.getType() == AccountType.SYSTEM) {
            throw new TransferForbiddenException("Only ledger administrators can move money out of system accounts");
        }
        if (!from.isOwnedBy(caller.subject())) {
            throw new TransferForbiddenException("You can only transfer from accounts you own");
        }
    }

    /** 6. Queue the event in the outbox, inside this same transaction. */
    private void queueEvent(TransferResponse transfer) {
        UUID eventId = UUID.randomUUID();
        TransferCompletedEvent event = new TransferCompletedEvent(
                eventId, TransferCompletedEvent.TYPE, transfer.transferId(),
                transfer.fromAccountId(), transfer.toAccountId(), transfer.amount(),
                transfer.currency(), transfer.description(), transfer.createdAt());
        outboxWriter.write(eventId, "Transfer", transfer.transferId(), TransferCompletedEvent.TYPE, event);
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