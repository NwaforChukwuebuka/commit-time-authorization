package io.github.omerbar4.paymentledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.omerbar4.paymentledger.domain.AttestationKeys;
import io.github.omerbar4.paymentledger.domain.CommitOutcome;
import io.github.omerbar4.paymentledger.domain.CommitReason;
import io.github.omerbar4.paymentledger.dto.CreateGrantRequest;
import io.github.omerbar4.paymentledger.service.CommitAuthorizationService;
import io.github.omerbar4.paymentledger.service.CommitResult;
import io.github.omerbar4.paymentledger.service.GrantService;
import io.github.omerbar4.paymentledger.service.PaymentEventCommand;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class CommitDebitIntegrationTest extends AbstractIntegrationTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-11-23T12:00:00Z");
    private static final Instant NOW = ISSUED_AT.plusSeconds(5);
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    @DynamicPropertySource
    static void payerRole(DynamicPropertyRegistry registry) {
        KeyPair keys = AttestationKeys.generate();
        registry.add("cta.role", () -> "PAYER");
        registry.add("cta.institution-id", () -> "BANKA");
        registry.add("cta.counterparty-institution-id", () -> "BANKB");
        registry.add("cta.attestation.private-key-pem", () -> AttestationKeys.toPem(keys.getPrivate()));
        registry.add("cta.attestation.payer-public-key-pem", () -> AttestationKeys.toPem(keys.getPublic()));
    }

    @Autowired
    private CommitAuthorizationService commit;

    @Autowired
    private GrantService grantService;

    @Test
    void oneDebitConsumesTheGrantAndAReplayDoesNotDebitAgain() {
        UUID payer = createFundedAccount("NGN", "100.00");
        UUID payee = UUID.randomUUID();
        String authRef = "AUTH-" + UUID.randomUUID();
        String eventId = "EVT-" + UUID.randomUUID();
        grantService.create(new CreateGrantRequest(payer, payee, AMOUNT, "NGN"), authRef);

        PaymentEventCommand command = new PaymentEventCommand(eventId, authRef, payer, payee, AMOUNT, "NGN",
                ISSUED_AT, NOW, "LEGIT");
        CommitResult first = commit.commitDebit(command);
        CommitResult second = commit.commitDebit(command);

        assertThat(first.outcome()).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(first.reason()).isEqualTo(CommitReason.OK);
        assertThat(second.outcome()).isEqualTo(CommitOutcome.DUPLICATE);
        assertThat(second.reason()).isEqualTo(CommitReason.P1_EVENT_ALREADY_COMMITTED);
        assertThat(jdbc.queryForObject("select count(*) from ledger where event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
        assertThat(balance(payer)).isEqualByComparingTo("90.00");
        assertThat(jdbc.queryForObject("select status from auth_grant where auth_ref = ?", String.class, authRef))
                .isEqualTo("CONSUMED");
    }

    @Test
    void debitWritesOneOutboxRowAndMarksTheGrantConsumed() {
        UUID payer = createFundedAccount("NGN", "100.00");
        UUID payee = UUID.randomUUID();
        String authRef = "AUTH-" + UUID.randomUUID();
        String eventId = "EVT-" + UUID.randomUUID();
        var grant = grantService.create(new CreateGrantRequest(payer, payee, AMOUNT, "NGN"), authRef);

        commit.commitDebit(new PaymentEventCommand(eventId, authRef, payer, payee, AMOUNT, "NGN", ISSUED_AT, NOW, "LEGIT"));

        assertThat(jdbc.queryForObject("select status from auth_grant where auth_ref = ?", String.class, authRef))
                .isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("select count(*) from outbox where kind = 'PACS008' and payload like ?",
                Integer.class, "%" + eventId + "%")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from transfer_correlation where end_to_end_id = ?", String.class,
                authRef)).isEqualTo("DEBITED");
        assertThat(grant.getPayeeAccountId()).isEqualTo(payee);
    }
}
