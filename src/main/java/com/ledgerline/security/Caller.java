package com.ledgerline.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;

/**
 * Who is making the request, taken from their verified JWT.
 * subject = the token's "sub" claim; admin = the token has the ledger:admin scope.
 */
public record Caller(String subject, boolean admin) {

    public static final String ADMIN_SCOPE = "ledger:admin";
    private static final int MAX_SUBJECT_LENGTH = 100;   // matches accounts.owner_id

    public static Caller from(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH) {
            throw new AccessDeniedException("Token has no usable subject");
        }
        String scope = jwt.getClaimAsString("scope");
        boolean admin = scope != null && Arrays.asList(scope.split(" ")).contains(ADMIN_SCOPE);
        return new Caller(subject, admin);
    }
}