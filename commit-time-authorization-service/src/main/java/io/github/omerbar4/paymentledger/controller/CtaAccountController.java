package io.github.omerbar4.paymentledger.controller;

import io.github.omerbar4.paymentledger.dto.AccountResponse;
import io.github.omerbar4.paymentledger.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "CTA accounts", description = "Balance, status, and freeze for commit-time checks")
public class CtaAccountController {

    private final AccountService accounts;

    public CtaAccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get balance and status")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accounts.get(id));
    }

    @PostMapping("/{id}/freeze")
    @Operation(summary = "Freeze an account",
            description = "Sets status to FROZEN. A debit commit sees P5 when this account is the payer. "
                    + "A credit commit sees P5 when this account is the payee.")
    public AccountResponse freeze(@PathVariable UUID id) {
        return AccountResponse.from(accounts.freeze(id));
    }
}
