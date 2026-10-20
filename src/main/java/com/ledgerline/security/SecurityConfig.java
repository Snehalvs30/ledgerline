package com.ledgerline.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * The API is an OAuth2 resource server: every request must carry a signed JWT
 * ("Authorization: Bearer <token>"), and each endpoint requires a specific scope.
 *
 * Scopes:
 *   accounts:read    GET  /accounts/{id}       (only your own accounts, unless admin)
 *   accounts:write   POST /accounts
 *   transfers:write  POST /transfers           (only from your own accounts, unless admin)
 *   ledger:admin     GET  /reconciliation, deposits from SYSTEM accounts, any account
 *
 * Tokens are signed with a shared secret (HS256) to keep local development simple.
 * In production you would validate tokens from an identity provider (Keycloak, Auth0,
 * Cognito...) using its public keys instead: see the README.
 */
@Configuration
@EnableConfigurationProperties(LedgerlineSecurityProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
                // Stateless token API: no cookies, no sessions, so CSRF protection doesn't apply.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Health checks and Prometheus scraping stay open. In production, expose
                        // these on a separate internal port instead of the public one.
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        // Only exists when ledgerline.security.dev-tokens.enabled=true.
                        .requestMatchers(HttpMethod.POST, "/dev/token").permitAll()
                        // Spring forwards errors here; without this, real errors would turn into 401s.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/accounts").hasAuthority("SCOPE_accounts:write")
                        .requestMatchers(HttpMethod.GET, "/accounts/**").hasAuthority("SCOPE_accounts:read")
                        .requestMatchers(HttpMethod.POST, "/transfers").hasAuthority("SCOPE_transfers:write")
                        .requestMatchers(HttpMethod.GET, "/reconciliation").hasAuthority("SCOPE_ledger:admin")
                        // Anything not listed above is refused, so new endpoints are closed by default.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /** Checks signature, expiry, issuer and audience of every incoming token. */
    @Bean
    public JwtDecoder jwtDecoder(LedgerlineSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<Collection<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(properties.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()), audience));
        return decoder;
    }

    /** Signs tokens. Used by the dev token endpoint and by tests. */
    @Bean
    public JwtEncoder jwtEncoder(LedgerlineSecurityProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    private static SecretKey signingKey(LedgerlineSecurityProperties properties) {
        return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}