package io.github.omerbar4.paymentledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A pacs.008 or pacs.002 waiting to be handed to the other bank. */
@Entity
@Table(name = "outbox")
public class OutboxMessage {

    @Id
    private UUID id;

    @Column(nullable = false, length = 16)
    private String kind;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxMessage() {
    }

    private OutboxMessage(String kind, String payload) {
        this.id = UUID.randomUUID();
        this.kind = kind;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public static OutboxMessage pending(String kind, String payload) {
        return new OutboxMessage(kind, payload);
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getKind() {
        return kind;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
