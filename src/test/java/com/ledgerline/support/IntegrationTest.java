package com.ledgerline.support;

import com.ledgerline.TestcontainersConfiguration;
import com.ledgerline.account.AccountResponse;
import com.ledgerline.account.CreateAccountRequest;
import com.ledgerline.reconciliation.ReconciliationReport;
import com.ledgerline.security.TokenIssuer;
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
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for integration tests. Starts the whole app on a random port, backed
 * by the Testcontainers PostgreSQL and Kafka, and calls it over real HTTP.
 *
 * Every test gets two identities:
 *   userToken   a fresh customer (unique subject) who owns the accounts the test creates
 *   adminToken  a ledger administrator, used for deposits, balance checks and reconciliation
 *
 * Tests never clean the database. Instead, every test creates its own fresh
 * accounts, so tests can't interfere with each other.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    /** Seeded by the V2 migration. */
    protected static final UUID FUNDING_ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    protected static final String[] USER_SCOPES = {"accounts:read", "accounts:write", "transfers:write"};

    @Value("${local.server.port}")
    private int port;

    @Autowired
    protected JsonMapper jsonMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TokenIssuer tokenIssuer;

    protected RestClient http;
    protected String userSubject;
    protected String userToken;
    protected String adminToken;

    @BeforeEach
    void setUpHttpClientAndTokens() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                // Don't throw on 4xx/5xx: tests want to assert on error responses.
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();

        userSubject = "user-" + UUID.randomUUID();
        userToken = tokenFor(userSubject, USER_SCOPES);
        adminToken = tokenFor("admin-" + UUID.randomUUID(),
                "accounts:read", "accounts:write", "transfers:write", "ledger:admin");
    }

    protected String tokenFor(String subject, String... scopes) {
        return tokenIssuer.issue(subject, List.of(scopes), Duration.ofMinutes(15));
    }

    // ---- Accounts --------------------------------------------------------------

    protected ResponseEntity<String> postAccount(Object body) {
        return postAccountAs(userToken, body);
    }

    protected ResponseEntity<String> postAccountAs(String token, Object body) {
        return http.post().uri("/accounts")
                .headers(h -> { if (token != null) h.setBearerAuth(token); })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    /** Creates an account owned by the test's user. */
    protected AccountResponse createAccount(String name, String currency) {
        return createAccountAs(userToken, name, currency);
    }

    protected AccountResponse createAccountAs(String token, String name, String currency) {
        ResponseEntity<String> response = postAccountAs(token, new CreateAccountRequest(name, currency));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return read(response, AccountResponse.class);
    }

    protected ResponseEntity<String> getAccount(String id) {
        return getAs(userToken, "/accounts/" + id);
    }

    /** Reads a balance as an admin, so tests can check any account. */
    protected long balanceOf(UUID accountId) {
        ResponseEntity<String> response = getAs(adminToken, "/accounts/" + accountId);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return read(response, AccountResponse.class).balance();
    }

    // ---- Transfers -------------------------------------------------------------

    /** Sends a transfer as the test's user. Pass idempotencyKey = null to leave the header out. */
    protected ResponseEntity<String> postTransfer(String idempotencyKey, TransferRequest request) {
        return postTransferAs(userToken, idempotencyKey, request);
    }

    protected ResponseEntity<String> postTransferAs(String token, String idempotencyKey, TransferRequest request) {
        RestClient.RequestBodySpec spec = http.post().uri("/transfers")
                .headers(h -> { if (token != null) h.setBearerAuth(token); })
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            spec = spec.header("Idempotency-Key", idempotencyKey);
        }
        return spec.body(request).retrieve().toEntity(String.class);
    }

    protected ResponseEntity<String> transfer(UUID from, UUID to, long amount) {
        return postTransfer(UUID.randomUUID().toString(), new TransferRequest(from, to, amount, null));
    }

    /** Puts money into an account the way production does: an admin transfer from the funding account. */
    protected void deposit(UUID to, long amount) {
        ResponseEntity<String> response = postTransferAs(adminToken, UUID.randomUUID().toString(),
                new TransferRequest(FUNDING_ACCOUNT_ID, to, amount, null));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    protected ReconciliationReport reconcile() {
        return read(getAs(adminToken, "/reconciliation"), ReconciliationReport.class);
    }

    // ---- Helpers ---------------------------------------------------------------

    /** GET with a bearer token. Pass token = null to send no token at all. */
    protected ResponseEntity<String> getAs(String token, String path) {
        return http.get().uri(path)
                .headers(h -> { if (token != null) h.setBearerAuth(token); })
                .retrieve()
                .toEntity(String.class);
    }

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