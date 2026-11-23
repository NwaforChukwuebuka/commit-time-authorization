package io.github.omerbar4.paymentledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.omerbar4.paymentledger.iso.Pacs002Mapper;
import io.github.omerbar4.paymentledger.iso.Pacs002View;
import io.github.omerbar4.paymentledger.iso.Pacs008Mapper;
import io.github.omerbar4.paymentledger.service.PaymentEventCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** pain.001 issues the grant, pacs.008 is posted by the worker, and the latest decision is pacs.002. */
class IsoIngressIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Pacs008Mapper pacs008;

    @Autowired
    private Pacs002Mapper pacs002;

    @Test
    void pain001ThenPacs008PostsOnceAndReplayIsRejected() throws Exception {
        UUID payer = createFundedAccount("NGN", "100.00");
        UUID payee = createAccount("NGN", false);
        String authRef = "AUTH-" + UUID.randomUUID();
        String eventId = "EVT-" + UUID.randomUUID();

        ResponseEntity<JsonNode> grant = postXml("/api/v1/iso/pain.001", pain(payer, payee, authRef), JsonNode.class);
        assertThat(grant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(grant.getBody().get("authRef").asText()).isEqualTo(authRef);
        assertThat(grant.getBody().get("status").asText()).isEqualTo("OPEN");

        Instant issuedAt = Instant.now();
        String pacs = pacs008.toXml(new PaymentEventCommand(eventId, authRef, payer, payee,
                new BigDecimal("10.00"), "NGN", issuedAt, issuedAt, "LEGIT"));
        ResponseEntity<JsonNode> accepted = postXml("/api/v1/iso/pacs.008", pacs, JsonNode.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody().get("eventId").asText()).isEqualTo(eventId);

        Pacs002View committed = pacs002.read(awaitXml(eventId, "ACCP"));
        assertThat(committed.transactionStatus()).isEqualTo("ACCP");
        assertThat(committed.reason()).isEqualTo("OK");

        ResponseEntity<JsonNode> replay = postXml("/api/v1/iso/pacs.008", pacs, JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Pacs002View duplicate = pacs002.read(awaitXml(eventId, "P1_EVENT_ALREADY_COMMITTED"));
        assertThat(duplicate.transactionStatus()).isEqualTo("RJCT");
        assertThat(duplicate.reason()).isEqualTo("P1_EVENT_ALREADY_COMMITTED");

        assertThat(ledgerRows(eventId)).isEqualTo(1);
        assertThat(balance(payer)).isEqualByComparingTo("90.00");
        assertThat(balance(payee)).isEqualByComparingTo("10.00");

        JsonNode decisions = http.getForObject("/api/v1/decisions?eventId=" + eventId, JsonNode.class);
        List<String> reasons = new ArrayList<>();
        decisions.forEach(node -> reasons.add(node.get("reason").asText()));
        assertThat(reasons).containsExactly("OK", "P1_EVENT_ALREADY_COMMITTED");
    }

    private String awaitXml(String eventId, String marker) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_XML));
        String last = "";
        for (int attempt = 0; attempt < 50; attempt++) {
            ResponseEntity<String> response = http.exchange("/api/v1/decisions?eventId=" + eventId, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            last = response.getStatusCode() + " " + response.getBody();
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null
                    && response.getBody().contains(marker)) {
                return response.getBody();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("decision containing " + marker + " was not written for " + eventId + ": " + last);
    }

    private <T> ResponseEntity<T> postXml(String path, String xml, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(xml, headers), type);
    }

    private static String pain(UUID payer, UUID payee, String authRef) {
        return """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.11">
                  <CstmrCdtTrfInitn>
                    <GrpHdr>
                      <MsgId>MSG-IT</MsgId>
                      <CreDtTm>2026-11-23T12:00:00Z</CreDtTm>
                      <NbOfTxs>1</NbOfTxs>
                    </GrpHdr>
                    <PmtInf>
                      <PmtInfId>PMT-IT</PmtInfId>
                      <PmtMtd>TRF</PmtMtd>
                      <NbOfTxs>1</NbOfTxs>
                      <DbtrAcct><Id><Othr><Id>%s</Id></Othr></Id></DbtrAcct>
                      <CdtTrfTxInf>
                        <PmtId><EndToEndId>%s</EndToEndId></PmtId>
                        <Amt><InstdAmt Ccy="NGN">10.00</InstdAmt></Amt>
                        <CdtrAcct><Id><Othr><Id>%s</Id></Othr></Id></CdtrAcct>
                      </CdtTrfTxInf>
                    </PmtInf>
                  </CstmrCdtTrfInitn>
                </Document>
                """.formatted(payer, authRef, payee);
    }

    private int ledgerRows(String eventId) {
        return jdbc.queryForObject("select count(*) from ledger where event_id = ?", Integer.class, eventId);
    }
}
