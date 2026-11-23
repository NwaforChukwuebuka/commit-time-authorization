package io.github.omerbar4.paymentledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.omerbar4.paymentledger.domain.AuthGrant;
import io.github.omerbar4.paymentledger.domain.CommitOutcome;
import io.github.omerbar4.paymentledger.domain.CommitReason;
import io.github.omerbar4.paymentledger.domain.GrantStatus;
import io.github.omerbar4.paymentledger.repository.AuthGrantRepository;
import io.github.omerbar4.paymentledger.service.CommitAuthorizationService;
import io.github.omerbar4.paymentledger.service.CommitResult;
import io.github.omerbar4.paymentledger.service.PaymentEventCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CommitIntraBankIntegrationTest extends AbstractIntegrationTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-11-23T12:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    @Autowired
    private CommitAuthorizationService commit;

    @Autowired
    private AuthGrantRepository grants;

    @Test
    void sequentialRedeliveryPostsASingleLedgerRow() {
        UUID payer = createFundedAccount("NGN", "100.00");
        UUID payee = createAccount("NGN", false);
        String authRef = "AUTH-" + UUID.randomUUID();
        String eventId = "EVT-" + UUID.randomUUID();
        openGrant(authRef, payer, payee);

        PaymentEventCommand command = command(eventId, authRef, payer, payee, ISSUED_AT.plusSeconds(5));
        CommitResult first = commit.commitIntraBank(command);
        CommitResult second = commit.commitIntraBank(command);

        assertThat(first.outcome()).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(first.reason()).isEqualTo(CommitReason.OK);
        assertThat(second.outcome()).isEqualTo(CommitOutcome.DUPLICATE);
        assertThat(second.reason()).isEqualTo(CommitReason.P1_EVENT_ALREADY_COMMITTED);

        assertThat(ledgerRows(eventId)).isEqualTo(1);
        UUID transactionId = jdbc.queryForObject("select transaction_id from ledger where event_id = ?", UUID.class,
                eventId);
        assertThat(countEntries(transactionId)).isEqualTo(2);
        assertThat(balance(payer)).isEqualByComparingTo("90.00");
        assertThat(balance(payee)).isEqualByComparingTo("10.00");

        AuthGrant grant = grants.findById(authRef).orElseThrow();
        assertThat(grant.getStatus()).isEqualTo(GrantStatus.CONSUMED);
        assertThat(grant.getConsumedByEventId()).isEqualTo(eventId);
    }

    @Test
    void rekeyedEventWithTheSameGrantDoesNotPostAgain() {
        UUID payer = createFundedAccount("NGN", "100.00");
        UUID payee = createAccount("NGN", false);
        String authRef = "AUTH-" + UUID.randomUUID();
        openGrant(authRef, payer, payee);
        Instant now = ISSUED_AT.plusSeconds(5);

        CommitResult first = commit.commitIntraBank(command("EVT-" + UUID.randomUUID(), authRef, payer, payee, now));
        String replayId = "EVT-" + UUID.randomUUID();
        CommitResult replay = commit.commitIntraBank(command(replayId, authRef, payer, payee, now.plusSeconds(1)));

        assertThat(first.outcome()).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(replay.outcome()).isEqualTo(CommitOutcome.REJECTED);
        assertThat(replay.reason()).isEqualTo(CommitReason.P3_GRANT_CONSUMED);
        assertThat(ledgerRows(replayId)).isZero();
        assertThat(balance(payer)).isEqualByComparingTo("90.00");
        assertThat(balance(payee)).isEqualByComparingTo("10.00");
    }

    @Test
    void eachPredicateFailsIndependently() {
        assertRejected(CommitReason.P2_STALE_EVENT, (payer, payee, authRef) ->
                command("EVT-" + UUID.randomUUID(), authRef, payer, payee, ISSUED_AT.plusSeconds(301)));

        assertRejected(CommitReason.P3_NO_GRANT, (payer, payee, authRef) -> {
            jdbc.update("delete from auth_grant where auth_ref = ?", authRef);
            return command("EVT-" + UUID.randomUUID(), authRef, payer, payee, ISSUED_AT.plusSeconds(5));
        });

        assertRejected(CommitReason.P3_GRANT_REVOKED, (payer, payee, authRef) -> {
            jdbc.update("update auth_grant set status = 'REVOKED' where auth_ref = ?", authRef);
            return command("EVT-" + UUID.randomUUID(), authRef, payer, payee, ISSUED_AT.plusSeconds(5));
        });

        assertRejected(CommitReason.P4_PAYLOAD_MISMATCH, (payer, payee, authRef) ->
                command("EVT-" + UUID.randomUUID(), authRef, payer, payee, ISSUED_AT.plusSeconds(5),
                        new BigDecimal("10.01")));

        assertRejected(CommitReason.P5_ACCOUNT_NOT_ACTIVE, (payer, payee, authRef) -> {
            jdbc.update("update accounts set status = 'FROZEN' where id = ?", payer);
            return command("EVT-" + UUID.randomUUID(), authRef, payer, payee, ISSUED_AT.plusSeconds(5));
        });
    }

    @Test
    void concurrentBurstPostsASingleLedgerRow() throws Exception {
        UUID payer = createFundedAccount("NGN", "100.00");
        UUID payee = createAccount("NGN", false);
        String authRef = "AUTH-" + UUID.randomUUID();
        String eventId = "EVT-" + UUID.randomUUID();
        openGrant(authRef, payer, payee);
        PaymentEventCommand command = command(eventId, authRef, payer, payee, ISSUED_AT.plusSeconds(5));

        List<CommitResult> results = IdempotencyIntegrationTest.runConcurrently(16,
                () -> commit.commitIntraBank(command));

        assertThat(results).filteredOn(r -> r.outcome() == CommitOutcome.COMMITTED).hasSize(1);
        assertThat(results).filteredOn(r -> r.reason() == CommitReason.P1_EVENT_ALREADY_COMMITTED).hasSize(15);
        assertThat(ledgerRows(eventId)).isEqualTo(1);
        assertThat(balance(payer)).isEqualByComparingTo("90.00");
        assertThat(balance(payee)).isEqualByComparingTo("10.00");
        assertThat(grants.findById(authRef).orElseThrow().getStatus()).isEqualTo(GrantStatus.CONSUMED);
    }

    @Test
    void agedReplayOfACommittedEventIsADuplicate() {
        UUID payer = createFundedAccount("NGN", "100.00");
        UUID payee = createAccount("NGN", false);
        String authRef = "AUTH-" + UUID.randomUUID();
        String eventId = "EVT-" + UUID.randomUUID();
        openGrant(authRef, payer, payee);

        CommitResult first = commit.commitIntraBank(command(eventId, authRef, payer, payee, ISSUED_AT.plusSeconds(5)));
        CommitResult aged = commit.commitIntraBank(
                command(eventId, authRef, payer, payee, ISSUED_AT.plusSeconds(86_400 + 3_605)));

        assertThat(first.outcome()).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(aged.outcome()).isEqualTo(CommitOutcome.DUPLICATE);
        assertThat(aged.reason()).isEqualTo(CommitReason.P1_EVENT_ALREADY_COMMITTED);
        assertThat(ledgerRows(eventId)).isEqualTo(1);
        assertThat(balance(payer)).isEqualByComparingTo("90.00");
        assertThat(balance(payee)).isEqualByComparingTo("10.00");
    }

    @Test
    void insufficientFundsDeclinesWithoutALedgerRow() {
        UUID payer = createAccount("NGN", false);
        UUID payee = createAccount("NGN", false);
        String authRef = "AUTH-" + UUID.randomUUID();
        String eventId = "EVT-" + UUID.randomUUID();
        openGrant(authRef, payer, payee);

        CommitResult result = commit.commitIntraBank(
                command(eventId, authRef, payer, payee, ISSUED_AT.plusSeconds(5)));

        assertThat(result.outcome()).isEqualTo(CommitOutcome.DECLINED);
        assertThat(result.reason()).isEqualTo(CommitReason.P6_INSUFFICIENT_FUNDS);
        assertThat(ledgerRows(eventId)).isZero();
        assertThat(balance(payer)).isEqualByComparingTo("0");
        assertThat(grants.findById(authRef).orElseThrow().getStatus()).isEqualTo(GrantStatus.OPEN);
    }

    private void assertRejected(CommitReason reason, Fault fault) {
        UUID payer = createFundedAccount("NGN", "100.00");
        UUID payee = createAccount("NGN", false);
        String authRef = "AUTH-" + UUID.randomUUID();
        openGrant(authRef, payer, payee);

        PaymentEventCommand command = fault.apply(payer, payee, authRef);
        CommitResult result = commit.commitIntraBank(command);

        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.outcome()).isNotEqualTo(CommitOutcome.COMMITTED);
        assertThat(ledgerRows(command.eventId())).isZero();
        assertThat(balance(payer)).isEqualByComparingTo("100.00");
        assertThat(balance(payee)).isEqualByComparingTo("0");
    }

    private void openGrant(String authRef, UUID payer, UUID payee) {
        grants.saveAndFlush(AuthGrant.open(authRef, payer, payee, AMOUNT, "NGN"));
    }

    private static PaymentEventCommand command(String eventId, String authRef, UUID payer, UUID payee, Instant now) {
        return command(eventId, authRef, payer, payee, now, AMOUNT);
    }

    private static PaymentEventCommand command(String eventId, String authRef, UUID payer, UUID payee, Instant now,
                                               BigDecimal amount) {
        return new PaymentEventCommand(eventId, authRef, payer, payee, amount, "NGN", ISSUED_AT, now, "LEGIT");
    }

    private int ledgerRows(String eventId) {
        return jdbc.queryForObject("select count(*) from ledger where event_id = ?", Integer.class, eventId);
    }

    @FunctionalInterface
    private interface Fault {
        PaymentEventCommand apply(UUID payer, UUID payee, String authRef);
    }
}
