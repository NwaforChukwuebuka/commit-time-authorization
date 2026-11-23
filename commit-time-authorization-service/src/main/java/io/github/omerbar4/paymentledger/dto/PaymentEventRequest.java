package io.github.omerbar4.paymentledger.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentEventRequest(
        @NotBlank @Size(max = 255) String eventId,
        @NotBlank @Size(max = 255) String authRef,
        @NotNull UUID payerAccountId,
        @NotNull UUID payeeAccountId,
        @Schema(example = "10.00")
        @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4)
        BigDecimal amount,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO 4217 code")
        String currency,
        @Schema(description = "When the customer issued the payment. Freshness is measured from this instant.")
        @NotNull Instant issuedAt,
        @Size(max = 64) String kind) {
}
