package io.github.omerbar4.paymentledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** This process's institution. {@code publicKeyPem} is the payer key Bank B verifies with. */
@Entity
@Table(name = "institution")
public class Institution {

    @Id
    @Column(length = 64)
    private String id;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "public_key_pem", nullable = false)
    private String publicKeyPem;

    @Column(name = "settlement_account_id")
    private UUID settlementAccountId;

    protected Institution() {
    }

    private Institution(String id, String publicKeyPem, UUID settlementAccountId) {
        this.id = id;
        this.publicKeyPem = publicKeyPem;
        this.settlementAccountId = settlementAccountId;
    }

    public static Institution create(String id, String publicKeyPem, UUID settlementAccountId) {
        return new Institution(id, publicKeyPem, settlementAccountId);
    }

    public void replacePublicKey(String publicKeyPem) {
        this.publicKeyPem = publicKeyPem;
    }

    public String getId() {
        return id;
    }

    public String getPublicKeyPem() {
        return publicKeyPem;
    }

    public UUID getSettlementAccountId() {
        return settlementAccountId;
    }
}
