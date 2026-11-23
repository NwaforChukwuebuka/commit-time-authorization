package io.github.omerbar4.paymentledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One committed payment effect. {@code event_id} is unique, so a second insert of the same
 * event cannot post again. The money movement itself is the cloned double-entry pair on
 * {@code transaction_id}.
 */
@Entity
@Table(name = "ledger")
public class LedgerEffect {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false, unique = true)
    private String eventId;

    @Column(name = "auth_ref", nullable = false, updatable = false)
    private String authRef;

    @Column(name = "payer_account_id", nullable = false, updatable = false)
    private UUID payerAccountId;

    @Column(name = "payee_account_id", nullable = false, updatable = false)
    private UUID payeeAccountId;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(nullable = false, length = 64, updatable = false)
    private String kind;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    protected LedgerEffect() {
    }

    private LedgerEffect(String eventId, String authRef, UUID payerAccountId, UUID payeeAccountId,
                         BigDecimal amount, String currency, String kind, UUID transactionId) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.authRef = authRef;
        this.payerAccountId = payerAccountId;
        this.payeeAccountId = payeeAccountId;
        this.amount = amount;
        this.currency = currency;
        this.kind = kind;
        this.transactionId = transactionId;
    }

    public static LedgerEffect posted(String eventId, String authRef, UUID payerAccountId, UUID payeeAccountId,
                                      BigDecimal amount, String currency, String kind, UUID transactionId) {
        return new LedgerEffect(eventId, authRef, payerAccountId, payeeAccountId, amount, currency, kind,
                transactionId);
    }

    public UUID getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAuthRef() {
        return authRef;
    }

    public UUID getTransactionId() {
        return transactionId;
    }
}
