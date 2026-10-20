package com.ledgerline.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Bound from ledgerline.security.* in application.yaml. */
@ConfigurationProperties(prefix = "ledgerline.security")
public record LedgerlineSecurityProperties(
        String jwtSecret,
        String issuer,
        String audience,
        Duration tokenTtl
) {
    public LedgerlineSecurityProperties {
        // HS256 needs a key of at least 256 bits. Refuse to start with a weak one.
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("ledgerline.security.jwt-secret must be at least 32 bytes");
        }
    }
}