package com.ledgerline.account;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID id) {
        super("Account " + id + " does not exist");
    }
}