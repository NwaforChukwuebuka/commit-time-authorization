package io.github.omerbar4.paymentledger.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.omerbar4.paymentledger.domain.AuthGrant;
import io.github.omerbar4.paymentledger.domain.GrantStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record GrantResponse(
        String authRef,
        UUID payerAccountId,
        UUID payeeAccountId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        GrantStatus status,
        String payloadHash,
        String consumedByEventId) {

    public static GrantResponse from(AuthGrant grant) {
        return new GrantResponse(grant.getAuthRef(), grant.getPayerAccountId(), grant.getPayeeAccountId(),
                grant.getAmount(), grant.getCurrency(), grant.getStatus(), grant.getPayloadHash(),
                grant.getConsumedByEventId());
    }
}
