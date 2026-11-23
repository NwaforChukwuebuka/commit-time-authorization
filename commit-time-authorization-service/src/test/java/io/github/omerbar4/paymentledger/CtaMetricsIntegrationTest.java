package io.github.omerbar4.paymentledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.omerbar4.paymentledger.domain.AuthGrant;
import io.github.omerbar4.paymentledger.domain.CommitOutcome;
import io.github.omerbar4.paymentledger.domain.CommitReason;
import io.github.omerbar4.paymentledger.repository.AuthGrantRepository;
import io.github.omerbar4.paymentledger.service.CommitAuthorizationService;
import io.github.omerbar4.paymentledger.service.CommitResult;
import io.github.omerbar4.paymentledger.service.PaymentEventCommand;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Commit-time counters and the structured commit log. Counters are shared across the test JVM,
 * so assertions use the delta produced by this test.
 */
@AutoConfigureObservability(tracing = false)
@ExtendWith(OutputCaptureExtension.class)
class CtaMetricsIntegrationTest extends AbstractIntegrationTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-11-23T12:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    @Autowired
    private CommitAuthorizationService commit;

    @Autowired
    private AuthGrantRepository grants;

    @Autowired
    private MeterRegistry registry;

    @Test
    void commitAndReplayAreCountedByReason(CapturedOutput output) {
        UUID payer = createFundedAccount("NGN", "100.00");
        UUID payee = createAccount("NGN", false);
        String authRef = "AUTH-" + UUID.randomUUID();
        String eventId = "EVT-" + UUID.randomUUID();
        grants.saveAndFlush(AuthGrant.open(authRef, payer, payee, AMOUNT, "NGN"));

        double committed = commits("intra", "committed", "OK");
        double duplicate = commits("intra", "duplicate", "P1_EVENT_ALREADY_COMMITTED");

        PaymentEventCommand command = new PaymentEventCommand(eventId, authRef, payer, payee, AMOUNT, "NGN",
                ISSUED_AT, ISSUED_AT.plusSeconds(5), "LEGIT");
        CommitResult first = commit.commitIntraBank(command);
        CommitResult second = commit.commitIntraBank(command);

        assertThat(first.outcome()).isEqualTo(CommitOutcome.COMMITTED);
        assertThat(first.reason()).isEqualTo(CommitReason.OK);
        assertThat(second.reason()).isEqualTo(CommitReason.P1_EVENT_ALREADY_COMMITTED);
        assertThat(commits("intra", "committed", "OK") - committed).isEqualTo(1);
        assertThat(commits("intra", "duplicate", "P1_EVENT_ALREADY_COMMITTED") - duplicate).isEqualTo(1);

        ResponseEntity<String> scrape = http.getForEntity("/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = scrape.getBody();
        assertThat(body)
                .contains("cta_commits_total{")
                .contains("role=\"intra\"")
                .contains("outcome=\"committed\"")
                .contains("reason=\"OK\"")
                .contains("outcome=\"duplicate\"")
                .contains("reason=\"P1_EVENT_ALREADY_COMMITTED\"")
                .doesNotContain(eventId)
                .doesNotContain(authRef)
                .doesNotContain(payer.toString())
                .doesNotContain(payee.toString())
                .doesNotContain("10.00");

        assertThat(output.getAll())
                .contains("\"message\":\"payment commit\"")
                .contains("\"cta.role\":\"intra\"")
                .contains("\"decision.outcome\":\"COMMITTED\"")
                .contains("\"decision.reason\":\"OK\"")
                .contains("\"decision.outcome\":\"DUPLICATE\"")
                .contains("\"decision.reason\":\"P1_EVENT_ALREADY_COMMITTED\"")
                .doesNotContain(eventId)
                .doesNotContain(payer.toString());
    }

    private double commits(String role, String outcome, String reason) {
        Counter counter = registry.find("cta.commits")
                .tags("role", role, "outcome", outcome, "reason", reason)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
