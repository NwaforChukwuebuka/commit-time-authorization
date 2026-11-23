package io.github.omerbar4.paymentledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** Single-use customer approval. Consumed in the same transaction as the ledger post. */
@Entity
@Table(name = "auth_grant")
public class AuthGrant {

    @Id
    @Column(name = "auth_ref", length = 255)
    private String authRef;

    @Column(name = "payer_account_id", nullable = false, updatable = false)
    private UUID payerAccountId;

    @Column(name = "payee_account_id", nullable = false, updatable = false)
    private UUID payeeAccountId;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GrantStatus status;

    @Column(name = "payload_hash", nullable = false, length = 64, updatable = false)
    private String payloadHash;

    @Column(name = "consumed_by_event_id")
    private String consumedByEventId;

    protected AuthGrant() {
    }

    private AuthGrant(String authRef, UUID payerAccountId, UUID payeeAccountId, BigDecimal amount,
                      String currency, String payloadHash) {
        this.authRef = authRef;
        this.payerAccountId = payerAccountId;
        this.payeeAccountId = payeeAccountId;
        this.amount = amount;
        this.currency = currency;
        this.status = GrantStatus.OPEN;
        this.payloadHash = payloadHash;
    }

    public static AuthGrant open(String authRef, UUID payerAccountId, UUID payeeAccountId,
                                 BigDecimal amount, String currency) {
        BigDecimal normalized = Money.normalize(amount);
        String hash = PayloadHasher.hash(payerAccountId.toString(), payeeAccountId.toString(),
                normalized.toPlainString(), authRef);
        return new AuthGrant(authRef, payerAccountId, payeeAccountId, normalized, currency, hash);
    }

    public void consume(String eventId) {
        if (status != GrantStatus.OPEN) {
            throw new IllegalStateException("Grant " + authRef + " is " + status);
        }
        this.status = GrantStatus.CONSUMED;
        this.consumedByEventId = eventId;
    }

    public void revoke() {
        if (status == GrantStatus.CONSUMED) {
            throw new IllegalStateException("Grant " + authRef + " is CONSUMED");
        }
        this.status = GrantStatus.REVOKED;
    }

    public String getAuthRef() {
        return authRef;
    }

    public UUID getPayerAccountId() {
        return payerAccountId;
    }

    public UUID getPayeeAccountId() {
        return payeeAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public GrantStatus getStatus() {
        return status;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public String getConsumedByEventId() {
        return consumedByEventId;
    }
}
