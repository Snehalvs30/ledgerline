package com.ledgerline.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Moves events from the outbox table to Kafka.
 *
 * Delivery is AT LEAST ONCE: if the app crashes after Kafka accepts an event but
 * before published_at is committed, that event is sent again on the next run.
 * Consumers handle this by deduplicating on the event ID.
 *
 * Tuned after load testing:
 *  v1 sent one event and waited for Kafka's confirmation before sending the next.
 *  v2 pipelines: sends a whole batch, then collects the confirmations.
 *  v3 (this) drains: while batches come back full, it immediately takes the next one
 *     instead of sleeping for the poll interval, so a backlog is worked off at full speed.
 *     Each batch is its own short transaction, so row locks are never held for long.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Upper bound per scheduled run, so one run never monopolises the thread. */
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String topic;
    private final int batchSize;

    public OutboxRelay(OutboxEventRepository repository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       TransactionTemplate transactionTemplate,
                       @Value("${ledgerline.outbox.topic}") String topic,
                       @Value("${ledgerline.outbox.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ledgerline.outbox.poll-interval-ms:500}")
    public void publishPending() {
        for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) {
            Integer published = transactionTemplate.execute(status -> publishOneBatch());
            // A partial batch means we've caught up (or Kafka failed): wait for the next run.
            if (published == null || published < batchSize) {
                return;
            }
        }
    }

    /** Publishes one batch inside the caller's transaction. Returns how many were confirmed. */
    private int publishOneBatch() {
        List<OutboxEvent> batch = repository.lockNextBatch(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        // 1. Send everything without waiting. The producer keeps the order within each
        //    partition (idempotent producer, the default), so pipelining is safe.
        List<CompletableFuture<SendResult<String, String>>> sends = batch.stream()
                .map(event -> kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayload()))
                .toList();

        // 2. Collect confirmations in order. Stop marking at the first failure: anything
        //    after it stays unpublished and is retried next run. Some of those may have
        //    actually reached Kafka, so they'll be sent twice, which consumers tolerate.
        int published = 0;
        for (int i = 0; i < batch.size(); i++) {
            OutboxEvent event = batch.get(i);
            try {
                sends.get(i).get(10, TimeUnit.SECONDS);
                event.markPublished();
                published++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.recordFailure("Interrupted while publishing");
                return published;
            } catch (Exception e) {
                event.recordFailure(e.toString());
                log.warn("Kafka unavailable, will retry outbox event {} (attempt {}): {}",
                        event.getId(), event.getAttempts(), e.getMessage());
                return published;
            }
        }
        return published;
    }
}