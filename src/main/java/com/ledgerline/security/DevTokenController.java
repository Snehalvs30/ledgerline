package com.ledgerline.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

/**
 * LOCAL DEVELOPMENT ONLY. Hands out tokens so you can try the API with curl and k6.
 * The bean doesn't exist unless ledgerline.security.dev-tokens.enabled=true
 * (the "local" profile), so the endpoint is simply absent everywhere else.
 */
@RestController
@RequestMapping("/dev/token")
@ConditionalOnProperty(prefix = "ledgerline.security.dev-tokens", name = "enabled", havingValue = "true")
public class DevTokenController {

    private static final Set<String> ALLOWED_SCOPES =
            Set.of("accounts:read", "accounts:write", "transfers:write", Caller.ADMIN_SCOPE);

    private final TokenIssuer tokenIssuer;

    public DevTokenController(TokenIssuer tokenIssuer) {
        this.tokenIssuer = tokenIssuer;
    }

    public record TokenRequest(@NotBlank @Size(max = 100) String subject, @NotEmpty List<String> scopes) {
    }

    public record TokenResponse(String accessToken, String tokenType) {
    }

    @PostMapping
    public TokenResponse issue(@Valid @RequestBody TokenRequest request) {
        if (!ALLOWED_SCOPES.containsAll(request.scopes())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Allowed scopes: " + ALLOWED_SCOPES);
        }
        return new TokenResponse(tokenIssuer.issue(request.subject(), request.scopes()), "Bearer");
    }
}