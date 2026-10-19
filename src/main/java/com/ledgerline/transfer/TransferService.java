package com.ledgerline.transfer;

import com.ledgerline.account.Account;
import com.ledgerline.account.AccountNotFoundException;
import com.ledgerline.account.AccountRepository;
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

    private final AccountRepository accountRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final PostingRepository postingRepository;

    public TransferService(AccountRepository accountRepository,
                           JournalEntryRepository journalEntryRepository,
                           PostingRepository postingRepository) {
        this.accountRepository = accountRepository;
        this.journalEntryRepository = journalEntryRepository;
        this.postingRepository = postingRepository;
    }

    /**
     * Everything here runs in ONE database transaction: either all of it is
     * saved, or none of it is.
     */
    @Transactional
    public TransferResponse transfer(TransferRequest request) {
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

        // The balance changes on `from` and `to` are saved automatically at commit,
        // because Hibernate tracks changes to entities loaded in this transaction.
        return new TransferResponse(entry.getId(), fromId, toId, amount,
                from.getCurrency(), entry.getDescription(), entry.getCreatedAt());
    }
}