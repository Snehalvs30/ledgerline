package com.ledgerline.account;

import com.ledgerline.security.Caller;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** Creates an account owned by the caller. Returns 201 Created with a Location header. */
    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request,
                                                  @AuthenticationPrincipal Jwt jwt,
                                                  UriComponentsBuilder uriBuilder) {
        Account account = accountService.create(request, Caller.from(jwt));
        URI location = uriBuilder.path("/accounts/{id}").buildAndExpand(account.getId()).toUri();
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    /** Returns one of the caller's accounts, or 404 if it doesn't exist or isn't theirs. */
    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return AccountResponse.from(accountService.getVisibleTo(id, Caller.from(jwt)));
    }
}