package com.ledgerline.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Moves events from the outbox table to Kafka.
 *
 * Delivery is AT LEAST ONCE: if the app crashes after Kafka accepts an event but
 * before published_at is committed, that event is sent again on the next run.
 * Consumers handle this by deduplicating on the event ID.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final int batchSize;

    public OutboxRelay(OutboxEventRepository repository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       @Value("${ledgerline.outbox.topic}") String topic,
                       @Value("${ledgerline.outbox.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ledgerline.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repository.lockNextBatch(batchSize);

        for (OutboxEvent event : batch) {
            try {
                // Wait for Kafka to confirm (acks=all by default) before marking it published.
                kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayload())
                        .get(10, TimeUnit.SECONDS);
                event.markPublished();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.recordFailure("Interrupted while publishing");
                return;
            } catch (Exception e) {
                event.recordFailure(e.toString());
                log.warn("Kafka unavailable, will retry outbox event {} (attempt {}): {}",
                        event.getId(), event.getAttempts(), e.getMessage());
                // Stop at the first failure so later events are never published ahead of earlier ones.
                return;
            }
        }

        if (!batch.isEmpty()) {
            log.info("Published {} outbox event(s) to {}", batch.size(), topic);
        }
    }
}