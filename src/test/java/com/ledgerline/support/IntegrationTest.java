package com.ledgerline.support;

import com.ledgerline.TestcontainersConfiguration;
import com.ledgerline.account.AccountResponse;
import com.ledgerline.account.CreateAccountRequest;
import com.ledgerline.transfer.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for integration tests. Starts the whole app on a random port, backed
 * by the Testcontainers PostgreSQL and Kafka, and calls it over real HTTP.
 *
 * Tests never clean the database. Instead, every test creates its own fresh
 * accounts, so tests can't interfere with each other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    /** Seeded by the V2 migration. */
    protected static final UUID FUNDING_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Value("${local.server.port}")
    private int port;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    protected RestClient http;

    @BeforeEach
    void setUpHttpClient() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                // Don't throw on 4xx/5xx: tests want to assert on error responses.
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    // ---- Accounts --------------------------------------------------------------

    protected ResponseEntity<String> postAccount(Object body) {
        return http.post().uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    protected AccountResponse createAccount(String name, String currency) {
        ResponseEntity<String> response = postAccount(new CreateAccountRequest(name, currency));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return read(response, AccountResponse.class);
    }

    protected ResponseEntity<String> getAccount(String id) {
        return http.get().uri("/accounts/{id}", id).retrieve().toEntity(String.class);
    }

    protected long balanceOf(UUID accountId) {
        ResponseEntity<String> response = getAccount(accountId.toString());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return read(response, AccountResponse.class).balance();
    }

    // ---- Transfers -------------------------------------------------------------

    /** Sends a transfer. Pass idempotencyKey = null to leave the header out. */
    protected ResponseEntity<String> postTransfer(String idempotencyKey, TransferRequest request) {
        RestClient.RequestBodySpec spec = http.post().uri("/transfers")
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            spec = spec.header("Idempotency-Key", idempotencyKey);
        }
        return spec.body(request).retrieve().toEntity(String.class);
    }

    protected ResponseEntity<String> transfer(UUID from, UUID to, long amount) {
        return postTransfer(UUID.randomUUID().toString(), new TransferRequest(from, to, amount, null));
    }

    /** Puts money into an account the way production does: a transfer from the funding account. */
    protected void deposit(UUID to, long amount) {
        ResponseEntity<String> response = transfer(FUNDING_ACCOUNT_ID, to, amount);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    // ---- Helpers ---------------------------------------------------------------

    protected <T> T read(ResponseEntity<String> response, Class<T> type) {
        return jsonMapper.readValue(response.getBody(), type);
    }

    /** Polls until the condition is true, for things that happen asynchronously (outbox, Kafka). */
    protected static void awaitUntil(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Condition not met within " + timeout);
    }

    /** The "title" and "status" fields of an RFC 9457 problem-details error. */
    protected record Problem(String title, Integer status, String detail) {
    }
}