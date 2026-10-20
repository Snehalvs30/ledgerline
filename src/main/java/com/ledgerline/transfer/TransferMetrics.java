package com.ledgerline.transfer;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counts transfer outcomes. Shows up in Prometheus as
 * ledger_transfers_total{outcome="created" | "replayed" | "insufficient_funds" | ...}.
 */
@Component
public class TransferMetrics {

    private final MeterRegistry registry;

    public TransferMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordOutcome(String outcome) {
        registry.counter("ledger.transfers", "outcome", outcome).increment();
    }
}