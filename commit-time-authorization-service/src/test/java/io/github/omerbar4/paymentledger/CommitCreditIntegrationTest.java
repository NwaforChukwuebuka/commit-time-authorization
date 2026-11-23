package io.github.omerbar4.paymentledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.omerbar4.paymentledger.domain.AttestationKeys;
import io.github.omerbar4.paymentledger.domain.CommitOutcome;
import io.github.omerbar4.paymentledger.domain.CommitReason;
import io.github.omerbar4.paymentledger.domain.DebitAttestation;
import io.github.omerbar4.paymentledger.service.CommitAuthorizationService;
import io.github.omerbar4.paymentledger.service.CommitResult;
import io.github.omerbar4.paymentledger.service.PaymentEventCommand;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class CommitCreditIntegrationTest extends AbstractIntegrationTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-11-23T12:00:00Z");
    private static final Instant NOW = ISSUED_AT.plusSeconds(5);
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");
    private static KeyPair keys;

    @DynamicPropertySource
    static void payeeRole(DynamicPropertyRegistry registry) {
        keys = AttestationKeys.generate();
        registry.add("cta.role", () -> "PAYEE");
        registry.add("cta.institution-id", () -> "BANKB");
        registry.add("cta.attestation.payer-public-key-pem", () -> AttestationKeys.toPem(keys.getPublic()));
    }

    @Autowired
    private CommitAuthorizationService commit;

    @Test
    void twoDeliveriesCreditOnceAndARekeyedMessageIsRejected() {
        UUID payer = UUID.randomUUID();
        UUID payee = createAccount("NGN", false);
        String endToEndId = "E2E-" + UUID.randomUUID();
        String eventId = "EVT-" + UUID.randomUUID();
        PaymentEventCommand command = credit(eventId, endToEndId, payer, payee, AMOUNT, eventId);

        CommitResult first = commit.commitCredit(command);
        CommitResult second = commit.commitCredit(command);
        CommitResult rekeyed = commit.commitCredit(credit("EVT-REKEY-" + UUID.randomUUID(), endToEndId, payer, payee,
                AMOUNT, eventId));

        assertThat(first.outcome()).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(second.outcome()).isEqualTo(CommitOutcome.DUPLICATE);
        assertThat(second.reason()).isEqualTo(CommitReason.P1_EVENT_ALREADY_COMMITTED);
        assertThat(rekeyed.outcome()).isEqualTo(CommitOutcome.DUPLICATE);
        assertThat(rekeyed.reason()).isEqualTo(CommitReason.P1_TRANSFER_ALREADY_CREDITED);
        assertThat(jdbc.queryForObject("select count(*) from ledger where auth_ref = ?", Integer.class, endToEndId))
                .isEqualTo(1);
        assertThat(balance(payee)).isEqualByComparingTo("10.00");
    }

    @Test
    void frozenPayeeAndTamperedAmountAreRejected() {
        UUID payer = UUID.randomUUID();
        UUID frozen = createAccount("NGN", false);
        http.postForEntity("/api/v1/accounts/" + frozen + "/freeze", null, String.class);
        String frozenRef = "E2E-" + UUID.randomUUID();
        CommitResult frozenResult = commit.commitCredit(credit("EVT-" + UUID.randomUUID(), frozenRef, payer, frozen,
                AMOUNT, "EVT-DEBIT"));
        assertThat(frozenResult.outcome()).isEqualTo(CommitOutcome.REJECTED);
        assertThat(frozenResult.reason()).isEqualTo(CommitReason.P5_ACCOUNT_NOT_ACTIVE);
        assertThat(balance(frozen)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from outbox where kind = 'PACS002' and payload like ?",
                Integer.class, "%" + frozenRef + "%")).isEqualTo(1);

        UUID payee = createAccount("NGN", false);
        String tamperedRef = "E2E-" + UUID.randomUUID();
        String json = attestation(payer, payee, tamperedRef, "EVT-DEBIT", AMOUNT);
        PaymentEventCommand tampered = new PaymentEventCommand("EVT-" + UUID.randomUUID(), tamperedRef, payer, payee,
                new BigDecimal("30.00"), "NGN", ISSUED_AT, NOW, "LEGIT", json);
        CommitResult tamperResult = commit.commitCredit(tampered);
        assertThat(tamperResult.reason()).isEqualTo(CommitReason.P4_ATTESTATION_INVALID);
        assertThat(balance(payee)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from ledger where auth_ref = ?", Integer.class, tamperedRef))
                .isZero();
    }

    private PaymentEventCommand credit(String eventId, String endToEndId, UUID payer, UUID payee, BigDecimal amount,
                                       String debitEventId) {
        return new PaymentEventCommand(eventId, endToEndId, payer, payee, amount, "NGN", ISSUED_AT, NOW, "LEGIT",
                attestation(payer, payee, endToEndId, debitEventId, amount));
    }

    private static String attestation(UUID payer, UUID payee, String endToEndId, String debitEventId, BigDecimal amount) {
        return DebitAttestation.issue(payer.toString(), endToEndId, endToEndId, amount, "NGN", "BANKA", "BANKB",
                payee.toString(), debitEventId, NOW).sign(keys.getPrivate()).toJson();
    }
}
