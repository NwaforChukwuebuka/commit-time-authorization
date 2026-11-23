package io.github.omerbar4.paymentledger.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One instruction to post a transfer. {@code eventId} identifies the message.
 * {@code authRef} identifies the customer approval. They are not interchangeable.
 */
public record PaymentEventCommand(
        String eventId,
        String authRef,
        UUID payerAccountId,
        UUID payeeAccountId,
        BigDecimal amount,
        String currency,
        Instant issuedAt,
        Instant now,
        String kind,
        String attestationJson) {

    public PaymentEventCommand(String eventId, String authRef, UUID payerAccountId, UUID payeeAccountId,
                               BigDecimal amount, String currency, Instant issuedAt, Instant now, String kind) {
        this(eventId, authRef, payerAccountId, payeeAccountId, amount, currency, issuedAt, now, kind, null);
    }

    public PaymentEventCommand withAttestation(String attestationJson) {
        return new PaymentEventCommand(eventId, authRef, payerAccountId, payeeAccountId, amount, currency, issuedAt,
                now, kind, attestationJson);
    }
}
