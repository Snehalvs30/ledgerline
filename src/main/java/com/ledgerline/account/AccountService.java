package com.ledgerline.account;

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
    public Account create(CreateAccountRequest request) {
        Account account = Account.newCustomerAccount(request.name().trim(), request.currency());
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public Account getById(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }
}