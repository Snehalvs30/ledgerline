package com.ledgerline.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Two numbers that tell you whether events are flowing:
 *  - ledger_outbox_pending:             how many events are waiting for Kafka
 *  - ledger_outbox_oldest_age_seconds:  how long the oldest one has waited (the "lag")
 *
 * Gauges are read each time Prometheus scrapes (every 5s). Both queries use the
 * partial index on unpublished events, so they stay cheap.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        Gauge.builder("ledger.outbox.pending", jdbc, j -> queryDouble(j,
                        "SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL"))
                .description("Outbox events not yet published to Kafka")
                .register(registry);

        Gauge.builder("ledger.outbox.oldest.age", jdbc, j -> queryDouble(j, """
                        SELECT COALESCE(EXTRACT(EPOCH FROM now() - MIN(created_at)), 0)
                        FROM outbox_events WHERE published_at IS NULL
                        """))
                .baseUnit("seconds")
                .description("Age of the oldest unpublished outbox event")
                .register(registry);
    }

    private static double queryDouble(JdbcTemplate jdbc, String sql) {
        Double value = jdbc.queryForObject(sql, Double.class);
        return value == null ? 0 : value;
    }
}