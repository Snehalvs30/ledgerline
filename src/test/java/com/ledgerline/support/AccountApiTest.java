package com.ledgerline.account;

import com.ledgerline.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AccountApiTest extends IntegrationTest {

    @Test
    void createsCustomerAccountWithZeroBalance() {
        ResponseEntity<String> response = postAccount(new CreateAccountRequest("Alice", "USD"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        AccountResponse account = read(response, AccountResponse.class);
        assertThat(account.id()).isNotNull();
        assertThat(account.name()).isEqualTo("Bob");
        assertThat(account.type()).isEqualTo(AccountType.CUSTOMER);
        assertThat(account.currency()).isEqualTo("USD");
        assertThat(account.balance()).isZero();
        assertThat(response.getHeaders().getLocation()).hasPath("/accounts/" + account.id());
    }

    @Test
    void fetchesAnExistingAccount() {
        AccountResponse created = createAccount("Bob", "USD");

        ResponseEntity<String> response = getAccount(created.id().toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(read(response, AccountResponse.class)).isEqualTo(created);
    }

    @Test
    void ignoresBalanceAndTypeSentByTheClient() {
        // A malicious client tries to create a rich SYSTEM account.
        ResponseEntity<String> response = postAccount(Map.of(
                "name", "Mallory", "currency", "USD", "balance", 1_000_000, "type", "SYSTEM"));

        AccountResponse account = read(response, AccountResponse.class);
        assertThat(account.balance()).isZero();
        assertThat(account.type()).isEqualTo(AccountType.CUSTOMER);
    }

    @Test
    void rejectsLowercaseCurrency() {
        ResponseEntity<String> response = postAccount(new CreateAccountRequest("Bob", "usd"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("currency");
    }

    @Test
    void rejectsBlankName() {
        ResponseEntity<String> response = postAccount(new CreateAccountRequest("  ", "USD"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).contains("name");
    }

    @Test
    void returns404ForUnknownAccount() {
        ResponseEntity<String> response = getAccount("00000000-0000-0000-0000-00000000ffff");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(read(response, Problem.class).title()).isEqualTo("Account not found");
    }

    @Test
    void returns400ForMalformedId() {
        assertThat(getAccount("not-a-uuid").getStatusCode().value()).isEqualTo(400);
    }
}