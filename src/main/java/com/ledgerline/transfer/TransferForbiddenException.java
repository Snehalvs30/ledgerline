package com.ledgerline.transfer;

/** The caller is authenticated but not allowed to move money out of this account. */
public class TransferForbiddenException extends RuntimeException {

    public TransferForbiddenException(String message) {
        super(message);
    }
}