package com.ledgerline.account;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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

    /** Creates an account. Returns 201 Created with a Location header pointing to it. */
    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request,
                                                  UriComponentsBuilder uriBuilder) {
        Account account = accountService.create(request);
        URI location = uriBuilder.path("/accounts/{id}").buildAndExpand(account.getId()).toUri();
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    /** Returns one account, or 404 if it doesn't exist. */
    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accountService.getById(id));
    }
}