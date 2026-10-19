package com.ledgerline.transfer;

/** The transfer, plus whether it was replayed from an earlier request with the same key. */
public record TransferResult(TransferResponse response, boolean replayed) {
}