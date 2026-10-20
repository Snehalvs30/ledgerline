package com.ledgerline.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Creates signed JWTs. In production an identity provider does this job; here it powers
 * the local dev token endpoint and the integration tests.
 */
@Component
public class TokenIssuer {

    private final JwtEncoder encoder;
    private final LedgerlineSecurityProperties properties;

    public TokenIssuer(JwtEncoder encoder, LedgerlineSecurityProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    public String issue(String subject, Collection<String> scopes) {
        return issue(subject, scopes, properties.tokenTtl());
    }

    public String issue(String subject, Collection<String> scopes, Duration timeToLive) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(timeToLive))
                .claim("scope", String.join(" ", scopes))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}