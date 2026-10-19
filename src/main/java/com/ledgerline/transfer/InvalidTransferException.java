package com.ledgerline.transfer;

/** A transfer that is well-formed but not allowed, e.g. same account or different currencies. */
public class InvalidTransferException extends RuntimeException {

    public InvalidTransferException(String message) {
        super(message);
    }
}