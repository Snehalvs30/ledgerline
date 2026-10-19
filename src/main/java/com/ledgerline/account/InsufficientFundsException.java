package com.ledgerline.account;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID accountId, long balance, long requested) {
        super("Account " + accountId + " has balance " + balance + " but " + requested + " was requested");
    }
}