package com.ledgerline.outbox;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Saves an event to the outbox table. MANDATORY propagation means it must be called
 * inside an existing transaction (the transfer's), and fails loudly if it isn't.
 * That guarantees the event and the business change commit together.
 */
@Component
public class OutboxWriter {

    private final EntityManager entityManager;
    private final JsonMapper jsonMapper;

    public OutboxWriter(EntityManager entityManager, JsonMapper jsonMapper) {
        this.entityManager = entityManager;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID eventId, String aggregateType, UUID aggregateId, String eventType, Object payload) {
        String json = jsonMapper.writeValueAsString(payload);
        // persist() rather than repository.save(): the ID is assigned by us, and save()
        // would first run a SELECT to check whether the row already exists.
        entityManager.persist(new OutboxEvent(eventId, aggregateType, aggregateId, eventType, json));
    }
}