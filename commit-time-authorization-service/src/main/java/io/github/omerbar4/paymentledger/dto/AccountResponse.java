package io.github.omerbar4.paymentledger.dto;

import io.github.omerbar4.paymentledger.domain.Account;
import io.github.omerbar4.paymentledger.domain.AccountStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        String currency,
        boolean allowNegativeBalance,
        AccountStatus status,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getName(), account.getCurrency(),
                account.isAllowNegativeBalance(), account.getStatus(), account.getBalance(), account.getCreatedAt());
    }
}
