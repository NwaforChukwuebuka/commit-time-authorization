package io.github.omerbar4.paymentledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One inter-bank transfer on this bank's database. {@code endToEndId} is unique. */
@Entity
@Table(name = "transfer_correlation")
public class TransferCorrelation {

    @Id
    @Column(name = "end_to_end_id", length = 255)
    private String endToEndId;

    @Column(name = "auth_ref", nullable = false, length = 255)
    private String authRef;

    @Column(name = "debit_event_id", length = 255)
    private String debitEventId;

    @Column(name = "credit_event_id", length = 255)
    private String creditEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CorrelationState state;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column
    private String attestation;

    protected TransferCorrelation() {
    }

    private TransferCorrelation(String endToEndId, String authRef, String debitEventId, String creditEventId,
                                CorrelationState state, String attestation) {
        this.endToEndId = endToEndId;
        this.authRef = authRef;
        this.debitEventId = debitEventId;
        this.creditEventId = creditEventId;
        this.state = state;
        this.attestation = attestation;
    }

    public static TransferCorrelation debited(String endToEndId, String authRef, String debitEventId,
                                              String attestation) {
        return new TransferCorrelation(endToEndId, authRef, debitEventId, null, CorrelationState.DEBITED, attestation);
    }

    public static TransferCorrelation credited(String endToEndId, String authRef, String debitEventId,
                                               String creditEventId, String attestation) {
        return new TransferCorrelation(endToEndId, authRef, debitEventId, creditEventId, CorrelationState.CREDITED,
                attestation);
    }

    public void markCredited(String creditEventId) {
        if (state != CorrelationState.DEBITED) {
            return;
        }
        this.state = CorrelationState.CREDITED;
        this.creditEventId = creditEventId;
    }

    public void markRejected() {
        if (state != CorrelationState.DEBITED) {
            return;
        }
        this.state = CorrelationState.REJECTED;
    }

    public void markReversed() {
        if (state != CorrelationState.REJECTED) {
            throw new IllegalStateException("Transfer " + endToEndId + " is " + state);
        }
        this.state = CorrelationState.REVERSED;
    }

    public String getEndToEndId() {
        return endToEndId;
    }

    public String getAuthRef() {
        return authRef;
    }

    public String getDebitEventId() {
        return debitEventId;
    }

    public String getCreditEventId() {
        return creditEventId;
    }

    public CorrelationState getState() {
        return state;
    }

    public String getAttestation() {
        return attestation;
    }
}
