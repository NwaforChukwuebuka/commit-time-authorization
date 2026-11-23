package io.github.omerbar4.paymentledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.omerbar4.paymentledger.domain.GrantStatus;
import io.github.omerbar4.paymentledger.service.CommitWorker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/** Worker is paused so a second publish or a freeze lands before commit. */
@TestPropertySource(properties = "cta.ingress.worker-enabled=false")
class PaymentEventIngressIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CommitWorker worker;

    @Test
    void duplicateDeliveryPostsASingleLedgerRow() {
        UUID payer = createFundedAccount("NGN", "100.00");
        UUID payee = createAccount("NGN", false);
        String authRef = createGrant(payer, payee);
        String eventId = "EVT-" + UUID.randomUUID();

        ResponseEntity<JsonNode> first = postEvent(eventId, authRef, payer, payee, "transport-key");
        ResponseEntity<JsonNode> second = postEvent(eventId, authRef, payer, payee, "transport-key");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(worker.drain()).isEqualTo(2);

        assertThat(ledgerRows(eventId)).isEqualTo(1);
        assertThat(balance(payer)).isEqualByComparingTo("90.00");
        assertThat(balance(payee)).isEqualByComparingTo("10.00");
        assertThat(reasons(eventId)).contains("OK", "P1_EVENT_ALREADY_COMMITTED");
        assertThat(grantStatus(authRef)).isEqualTo(GrantStatus.CONSUMED.name());
    }

    @Test
    void freezeBetweenEnqueueAndCommitRejectsWithP5() {
        UUID payer = createFundedAccount("NGN", "100.00");
        UUID payee = createAccount("NGN", false);
        String authRef = createGrant(payer, payee);
        String eventId = "EVT-" + UUID.randomUUID();

        assertThat(postEvent(eventId, authRef, payer, payee, null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<JsonNode> frozen = http.postForEntity("/api/v1/accounts/" + payer + "/freeze", null, JsonNode.class);
        assertThat(frozen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(frozen.getBody().get("status").asText()).isEqualTo("FROZEN");

        assertThat(worker.drain()).isEqualTo(1);

        assertThat(ledgerRows(eventId)).isZero();
        assertThat(balance(payer)).isEqualByComparingTo("100.00");
        assertThat(balance(payee)).isEqualByComparingTo("0");
        assertThat(reasons(eventId)).containsExactly("P5_ACCOUNT_NOT_ACTIVE");
        assertThat(grantStatus(authRef)).isEqualTo(GrantStatus.OPEN.name());

        JsonNode account = http.getForObject("/api/v1/accounts/" + payer, JsonNode.class);
        assertThat(account.get("status").asText()).isEqualTo("FROZEN");
        assertThat(new BigDecimal(account.get("balance").asText())).isEqualByComparingTo("100.00");
    }

    @Test
    void revokedGrantIsRejectedAtCommit() {
        UUID payer = createFundedAccount("NGN", "100.00");
        UUID payee = createAccount("NGN", false);
        String authRef = createGrant(payer, payee);
        String eventId = "EVT-" + UUID.randomUUID();

        ResponseEntity<JsonNode> revoked = http.exchange("/api/v1/grants/" + authRef, HttpMethod.DELETE, null,
                JsonNode.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revoked.getBody().get("status").asText()).isEqualTo("REVOKED");

        assertThat(postEvent(eventId, authRef, payer, payee, null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(worker.drain()).isEqualTo(1);

        assertThat(ledgerRows(eventId)).isZero();
        assertThat(reasons(eventId)).containsExactly("P3_GRANT_REVOKED");
        assertThat(balance(payer)).isEqualByComparingTo("100.00");
    }

    private String createGrant(UUID payer, UUID payee) {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/v1/grants",
                Map.of("payerAccountId", payer, "payeeAccountId", payee, "amount", "10.00", "currency", "NGN"),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("authRef").asText();
    }

    private ResponseEntity<JsonNode> postEvent(String eventId, String authRef, UUID payer, UUID payee, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        Map<String, Object> body = Map.of(
                "eventId", eventId,
                "authRef", authRef,
                "payerAccountId", payer,
                "payeeAccountId", payee,
                "amount", "10.00",
                "currency", "NGN",
                "issuedAt", Instant.now().toString());
        return http.postForEntity("/api/v1/payment-events", new HttpEntity<>(body, headers), JsonNode.class);
    }

    private List<String> reasons(String eventId) {
        JsonNode decisions = http.getForObject("/api/v1/decisions?eventId=" + eventId, JsonNode.class);
        List<String> reasons = new ArrayList<>();
        decisions.forEach(node -> reasons.add(node.get("reason").asText()));
        return reasons;
    }

    private int ledgerRows(String eventId) {
        return jdbc.queryForObject("select count(*) from ledger where event_id = ?", Integer.class, eventId);
    }

    private String grantStatus(String authRef) {
        return jdbc.queryForObject("select status from auth_grant where auth_ref = ?", String.class, authRef);
    }
}
