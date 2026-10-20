package com.ledgerline.account;

import com.ledgerline.security.Caller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public Account create(CreateAccountRequest request, Caller caller) {
        Account account = Account.newCustomerAccount(request.name().trim(), request.currency(), caller.subject());
        return accountRepository.save(account);
    }

    /**
     * Returns the account only if the caller may see it: their own, or any account for an admin.
     * Someone else's account gets the same 404 as a missing one, so callers can't
     * discover which account IDs exist (OWASP API1: broken object-level authorization).
     */
    @Transactional(readOnly = true)
    public Account getVisibleTo(UUID id, Caller caller) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        if (!caller.admin() && !account.isOwnedBy(caller.subject())) {
            throw new AccountNotFoundException(id);
        }
        return account;
    }
}