# Failure tests

Each test runs while the k6 load test is running, then checks that the ledger is still
correct. Fill in the results with what you actually observe. Keep the numbers honest:
they go in the README and possibly your resume.

**Machine:** _(e.g. MacBook Air M2, 16 GB, everything on one laptop)_

## 1. Kafka outage during load

1. Start the load test.
2. After ~30 s: `docker compose stop kafka`
3. Watch Grafana: transfers keep succeeding; "Outbox events waiting" and "Outbox lag" climb.
4. After ~60 s: `docker compose start kafka`
5. Watch the outbox drain back to 0.
6. After the load test: `curl -s localhost:8080/reconciliation`

| Measure | Result |
|---|---|
| Transfer errors (5xx) during the outage | |
| Peak events waiting / peak lag | |
| Time to drain after Kafka returned | |
| Reconciliation afterwards | |
| Duplicate notifications (`SELECT event_id, COUNT(*) FROM notifications GROUP BY event_id HAVING COUNT(*) > 2;`) | |

## 2. App crash during load

1. Start the load test.
2. After ~30 s: stop the app in IntelliJ (red square). k6 reports connection errors; that is expected.
3. After ~15 s: start the app again.
4. After the load test: check reconciliation and the duplicate-notifications query.

| Measure | Result |
|---|---|
| Requests failed while the app was down | |
| Reconciliation afterwards | |
| Outbox events left unpublished after restart (`ledger_outbox_pending`) | |
| Duplicate notifications | |

## 3. Load test baseline (no failures)

| Measure | Result |
|---|---|
| Peak virtual users | 50 |
| Transfers per second (k6 `http_reqs` rate) | |
| p95 latency, POST /transfers | |
| p99 latency, POST /transfers | |
| 5xx errors | |
| Reconciliation afterwards | |