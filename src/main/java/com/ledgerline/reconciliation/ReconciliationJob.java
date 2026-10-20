package com.ledgerline.reconciliation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the reconciliation on a schedule and publishes the number of violations
 * as a metric. On Day 5 a Grafana alert will fire if this is ever above zero.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliationService;
    private final AtomicInteger violations = new AtomicInteger();

    public ReconciliationJob(ReconciliationService reconciliationService, MeterRegistry meterRegistry) {
        this.reconciliationService = reconciliationService;
        Gauge.builder("ledger.reconciliation.violations", violations, AtomicInteger::get)
                .description("Ledger invariant violations found by the last reconciliation run")
                .register(meterRegistry);
    }

    @Scheduled(initialDelayString = "${ledgerline.reconciliation.initial-delay-ms:10000}",
            fixedDelayString = "${ledgerline.reconciliation.interval-ms:60000}")
    public void run() {
        ReconciliationReport report = reconciliationService.run();
        violations.set(report.violations());

        if (report.balanced()) {
            log.info("Reconciliation passed. Totals by currency: {}", report.totalBalanceByCurrency());
        } else {
            log.error("Reconciliation FAILED with {} violation(s): {}", report.violations(), report);
        }
    }
}