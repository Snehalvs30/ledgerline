package com.ledgerline.transfer;

import com.ledgerline.account.AccountNotFoundException;
import com.ledgerline.account.InsufficientFundsException;
import com.ledgerline.idempotency.IdempotencyKeyReusedException;
import com.ledgerline.idempotency.InvalidIdempotencyKeyException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;
    private final TransferMetrics metrics;

    public TransferController(TransferService transferService, TransferMetrics metrics) {
        this.transferService = transferService;
        this.metrics = metrics;
    }

    /**
     * Requires an Idempotency-Key header (any unique string, e.g. a UUID).
     * Sending the same key and body again returns the original transfer instead of
     * moving money twice. The Idempotent-Replayed header says which case happened.
     */
    @PostMapping
    public ResponseEntity<TransferResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                   @Valid @RequestBody TransferRequest request) {
        TransferResult result;
        try {
            result = transferService.transfer(idempotencyKey, request);
        } catch (InsufficientFundsException e) {
            metrics.recordOutcome("insufficient_funds");
            throw e;
        } catch (InvalidTransferException | AccountNotFoundException e) {
            metrics.recordOutcome("rejected");
            throw e;
        } catch (IdempotencyKeyReusedException | InvalidIdempotencyKeyException e) {
            metrics.recordOutcome("bad_idempotency_key");
            throw e;
        } catch (RuntimeException e) {
            metrics.recordOutcome("error");
            throw e;
        }

        metrics.recordOutcome(result.replayed() ? "replayed" : "created");
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.response());
    }
}