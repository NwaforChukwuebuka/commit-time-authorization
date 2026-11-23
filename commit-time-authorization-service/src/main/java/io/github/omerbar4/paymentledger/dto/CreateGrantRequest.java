package io.github.omerbar4.paymentledger.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateGrantRequest(
        @NotNull UUID payerAccountId,
        @NotNull UUID payeeAccountId,
        @Schema(example = "10.00", description = "Authorized amount. Stored at ledger scale 4.")
        @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4)
        BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO 4217 code")
        String currency) {
}
