package io.github.omerbar4.paymentledger.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DebitAttestationTest {

    @Test
    void signatureVerifiesAndATamperedAmountDoesNot() {
        KeyPair keys = AttestationKeys.generate();
        Instant consumedAt = Instant.parse("2026-11-23T12:00:05Z");
        DebitAttestation signed = DebitAttestation.issue("payer", "E2E-1", "E2E-1", new BigDecimal("10.00"), "NGN",
                "BANKA", "BANKB", "payee", "EVT-1", consumedAt).sign(keys.getPrivate());

        DebitAttestation parsed = DebitAttestation.parse(signed.toJson());
        assertThat(parsed.verify(keys.getPublic())).isTrue();
        assertThat(parsed.amountMinor()).isEqualTo(100000L);
        assertThat(parsed.payloadHash()).startsWith("sha256:");

        DebitAttestation tampered = new DebitAttestation(parsed.version(), parsed.endToEndId(), parsed.authRef(),
                parsed.amountMinor() + 1, parsed.currency(), parsed.payerInstitutionId(), parsed.payeeInstitutionId(),
                parsed.cdtrAcctId(), parsed.payloadHash(), parsed.debitEventId(), parsed.consumedAt(), parsed.signature());
        assertThat(tampered.verify(keys.getPublic())).isFalse();
    }
}
