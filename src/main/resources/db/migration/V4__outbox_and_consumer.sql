-- V4: transactional outbox + consumer-side deduplication.

-- Events waiting to be published to Kafka. Written in the SAME transaction as the
-- transfer, so an event exists if and only if the transfer committed.
CREATE TABLE outbox_events (
                               id              UUID          PRIMARY KEY,          -- also the event ID consumers dedupe on
                               aggregate_type  VARCHAR(50)   NOT NULL,             -- e.g. 'Transfer'
                               aggregate_id    UUID          NOT NULL,             -- e.g. the transfer ID; used as the Kafka key
                               event_type      VARCHAR(100)  NOT NULL,             -- e.g. 'TransferCompleted'
                               payload         JSONB         NOT NULL,
                               created_at      TIMESTAMPTZ   NOT NULL,
                               published_at    TIMESTAMPTZ,                        -- NULL until Kafka confirms it
                               attempts        INT           NOT NULL DEFAULT 0,
                               last_error      VARCHAR(1000)
);

-- The relay only ever looks for unpublished events, oldest first.
-- A partial index stays small because published rows are left out.
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at, id) WHERE published_at IS NULL;

-- Which events each consumer has already handled. Kafka delivers at least once,
-- so duplicates are normal; this table turns them into no-ops.
CREATE TABLE processed_events (
                                  consumer      VARCHAR(100)  NOT NULL,
                                  event_id      UUID          NOT NULL,
                                  processed_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
                                  PRIMARY KEY (consumer, event_id)
);

-- The read model built by the notification consumer: "You sent / received ..."
CREATE TABLE notifications (
                               id          BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                               account_id  UUID          NOT NULL REFERENCES accounts (id),
                               event_id    UUID          NOT NULL,
                               message     VARCHAR(255)  NOT NULL,
                               created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_account ON notifications (account_id, created_at DESC);