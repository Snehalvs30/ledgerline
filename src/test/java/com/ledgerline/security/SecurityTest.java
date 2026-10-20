package com.ledgerline.security;

import com.ledgerline.account.AccountResponse;
import com.ledgerline.account.CreateAccountRequest;
import com.ledgerline.support.IntegrationTest;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityTest extends IntegrationTest {

    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    void requestWithoutTokenIsRejectedWith401() {
        assertThat(postAccountAs(null, new CreateAccountRequest("Alice", "USD")).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        // A forged token: right claims, wrong signing key.
        NimbusJwtEncoder forger = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                "a-completely-different-secret-key-1234567890".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ledgerline-dev").audience(List.of("ledgerline-api"))
                .subject("mallory").issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim("scope", "accounts:read accounts:write transfers:write ledger:admin")
                .build();
        String forged = forger.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThat(getAs(forged, "/reconciliation").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void expiredTokenIsRejected() {
        // Issued 10 minutes ago, expired 5 minutes ago: beyond the 60-second clock-skew allowance.
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ledgerline-dev").audience(List.of("ledgerline-api"))
                .subject("user-expired")
                .issuedAt(now.minus(Duration.ofMinutes(10)))
                .expiresAt(now.minus(Duration.ofMinutes(5)))
                .claim("scope", String.join(" ", USER_SCOPES))
                .build();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThat(postAccountAs(expired, new CreateAccountRequest("Alice", "USD")).getStatusCode().value())
                .isEqualTo(401);
    }

//    @Test
//    void expiredTokenIsRejected() {
//        // Expired 5 minutes ago (beyond the default 60-second clock-skew allowance).
//        String expired = tokenIssuer.issue("user-expired", List.of(USER_SCOPES), Duration.ofMinutes(-5));
//
//        assertThat(postAccountAs(expired, new CreateAccountRequest("Alice", "USD")).getStatusCode().value())
//                .isEqualTo(401);
//    }


    @Test
    void tokenWithoutTheRequiredScopeGets403() {
        String readOnly = tokenFor("reader", "accounts:read");

        assertThat(postAccountAs(readOnly, new CreateAccountRequest("Alice", "USD")).getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    void reconciliationRequiresAdminScope() {
        assertThat(getAs(userToken, "/reconciliation").getStatusCode().value()).isEqualTo(403);
        assertThat(getAs(adminToken, "/reconciliation").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void healthCheckStaysPublic() {
        assertThat(getAs(null, "/actuator/health").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void unknownEndpointsAreDeniedByDefault() {
        assertThat(getAs(adminToken, "/actuator/env").getStatusCode().value()).isIn(403, 404);
    }

    @Test
    void usersCannotSeeEachOthersAccounts() {
        AccountResponse alicesAccount = createAccount("Alice", "USD");
        String bobsToken = tokenFor("bob-" + alicesAccount.id(), USER_SCOPES);

        // Same 404 as a missing account, so Bob can't even learn that it exists.
        assertThat(getAs(bobsToken, "/accounts/" + alicesAccount.id()).getStatusCode().value()).isEqualTo(404);
        assertThat(getAs(userToken, "/accounts/" + alicesAccount.id()).getStatusCode().value()).isEqualTo(200);
    }
}