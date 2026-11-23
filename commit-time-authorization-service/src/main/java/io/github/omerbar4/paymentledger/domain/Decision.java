package io.github.omerbar4.paymentledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One commit attempt. A replay inserts another row; it does not update the first. */
@Entity
@Table(name = "decision")
public class Decision {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private String eventId;

    @Column(name = "auth_ref", nullable = false, updatable = false)
    private String authRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private CommitOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64, updatable = false)
    private CommitReason reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Decision() {
    }

    private Decision(String eventId, String authRef, CommitOutcome outcome, CommitReason reason) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.authRef = authRef;
        this.outcome = outcome;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public static Decision of(String eventId, String authRef, CommitOutcome outcome, CommitReason reason) {
        return new Decision(eventId, authRef, outcome, reason);
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

    public CommitOutcome getOutcome() {
        return outcome;
    }

    public CommitReason getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
