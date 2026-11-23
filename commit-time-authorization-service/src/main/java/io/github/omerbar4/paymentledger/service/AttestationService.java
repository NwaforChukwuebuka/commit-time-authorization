package io.github.omerbar4.paymentledger.service;

import io.github.omerbar4.paymentledger.config.CtaProperties;
import io.github.omerbar4.paymentledger.domain.Account;
import io.github.omerbar4.paymentledger.domain.AttestationKeys;
import io.github.omerbar4.paymentledger.domain.DebitAttestation;
import io.github.omerbar4.paymentledger.domain.Institution;
import io.github.omerbar4.paymentledger.domain.Money;
import io.github.omerbar4.paymentledger.domain.PayloadHasher;
import io.github.omerbar4.paymentledger.domain.PemSources;
import io.github.omerbar4.paymentledger.repository.InstitutionRepository;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Signs a debit attestation and checks one at credit time against the institution table. */
@Service
public class AttestationService {

    private final CtaProperties properties;
    private final InstitutionRepository institutions;
    private PrivateKey privateKey;

    public AttestationService(CtaProperties properties, InstitutionRepository institutions) {
        this.properties = properties;
        this.institutions = institutions;
    }

    @PostConstruct
    void loadSigningKey() {
        if (!"PAYER".equals(properties.getRole())) {
            return;
        }
        String pem = PemSources.resolve(properties.getAttestation().getPrivateKeyPem());
        if (pem == null || pem.isBlank()) {
            throw new IllegalStateException("cta.attestation.private-key-pem is required when cta.role=PAYER");
        }
        privateKey = AttestationKeys.readPrivate(pem);
    }

    public String sign(PaymentEventCommand command, BigDecimal amount) {
        if (privateKey == null) {
            throw new IllegalStateException("This process has no attestation signing key");
        }
        String counterparty = properties.getCounterpartyInstitutionId();
        if (counterparty == null || counterparty.isBlank()) {
            throw new IllegalStateException("cta.counterparty-institution-id is required when cta.role=PAYER");
        }
        Instant consumedAt = command.now() == null ? Instant.now() : command.now();
        return DebitAttestation.issue(command.payerAccountId().toString(), command.authRef(), command.authRef(),
                        amount, command.currency(), properties.getInstitutionId(), counterparty,
                        command.payeeAccountId().toString(), command.eventId(), consumedAt)
                .sign(privateKey)
                .toJson();
    }

    /**
     * @return the attestation when the signature and the message fields agree, otherwise empty
     */
    public java.util.Optional<DebitAttestation> verified(PaymentEventCommand command, BigDecimal amount) {
        if (command.attestationJson() == null || command.attestationJson().isBlank()) {
            return java.util.Optional.empty();
        }
        DebitAttestation attestation;
        try {
            attestation = DebitAttestation.parse(command.attestationJson());
        } catch (IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
        if (!attestation.verify(payerPublicKey())) {
            return java.util.Optional.empty();
        }
        String expectedHash = "sha256:" + PayloadHasher.hash(command.payerAccountId().toString(),
                command.payeeAccountId().toString(), amount.toPlainString(), command.authRef());
        if (!constantEquals(expectedHash, attestation.payloadHash())) {
            return java.util.Optional.empty();
        }
        BigDecimal attestedAmount = BigDecimal.valueOf(attestation.amountMinor(), Money.SCALE);
        if (attestation.version() != 1
                || !attestation.endToEndId().equals(command.authRef())
                || !attestation.authRef().equals(command.authRef())
                || !attestation.cdtrAcctId().equals(command.payeeAccountId().toString())
                || !attestation.currency().equals(command.currency())
                || !attestation.payeeInstitutionId().equals(properties.getInstitutionId())
                || attestedAmount.compareTo(amount) != 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(attestation);
    }

    private PublicKey payerPublicKey() {
        Institution institution = institutions.findById(properties.getInstitutionId())
                .orElseThrow(() -> new IllegalStateException("No institution " + properties.getInstitutionId()));
        return AttestationKeys.readPublic(institution.getPublicKeyPem());
    }

    private static boolean constantEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
