package io.github.omerbar4.paymentledger.iso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.omerbar4.paymentledger.domain.CommitOutcome;
import io.github.omerbar4.paymentledger.domain.CommitReason;
import io.github.omerbar4.paymentledger.domain.Decision;
import io.github.omerbar4.paymentledger.service.PaymentEventCommand;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IsoMapperTest {

    private static final UUID PAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PAYEE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant ISSUED = Instant.parse("2026-11-23T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-23T12:00:05Z");

    private final Pain001Mapper pain001 = new Pain001Mapper();
    private final Pacs008Mapper pacs008 = new Pacs008Mapper();
    private final Pacs002Mapper pacs002 = new Pacs002Mapper();

    @Test
    void pain001FixtureMapsGrantFieldsAndAuthRef() throws Exception {
        Pain001Grant grant = pain001.toGrant(resource("/iso20022/pain.001.001.11-grant.xml"));

        assertThat(grant.authRef()).isEqualTo("AUTH-SEMINAR-1");
        assertThat(grant.request().payerAccountId()).isEqualTo(PAYER);
        assertThat(grant.request().payeeAccountId()).isEqualTo(PAYEE);
        assertThat(grant.request().amount()).isEqualByComparingTo("10.00");
        assertThat(grant.request().currency()).isEqualTo("NGN");
    }

    @Test
    void pain001WithoutEndToEndIdLeavesAuthRefUnset() {
        String xml = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.11">
                  <CstmrCdtTrfInitn>
                    <GrpHdr><MsgId>MSG</MsgId><CreDtTm>2026-11-23T12:00:00Z</CreDtTm><NbOfTxs>1</NbOfTxs></GrpHdr>
                    <PmtInf>
                      <PmtInfId>PMT</PmtInfId><PmtMtd>TRF</PmtMtd><NbOfTxs>1</NbOfTxs>
                      <DbtrAcct><Id><Othr><Id>11111111-1111-1111-1111-111111111111</Id></Othr></Id></DbtrAcct>
                      <CdtTrfTxInf>
                        <Amt><InstdAmt Ccy="NGN">10.00</InstdAmt></Amt>
                        <CdtrAcct><Id><Othr><Id>22222222-2222-2222-2222-222222222222</Id></Othr></Id></CdtrAcct>
                      </CdtTrfTxInf>
                    </PmtInf>
                  </CstmrCdtTrfInitn>
                </Document>
                """;

        assertThat(pain001.toGrant(xml).authRef()).isNull();
    }

    @Test
    void seminarPacs008RoundTripsBusinessFields() throws Exception {
        PaymentEventCommand command = pacs008.toCommand(resource("/iso20022/pacs.008.001.07-seminar.xml"), NOW);

        assertThat(command.eventId()).isEqualTo("EVT-SEMINAR-1");
        assertThat(command.authRef()).isEqualTo("AUTH-SEMINAR-1");
        assertThat(command.payerAccountId()).isEqualTo(PAYER);
        assertThat(command.payeeAccountId()).isEqualTo(PAYEE);
        assertThat(command.amount()).isEqualByComparingTo("10.00");
        assertThat(command.currency()).isEqualTo("NGN");
        assertThat(command.issuedAt()).isEqualTo(ISSUED);
        assertThat(command.now()).isEqualTo(NOW);
        assertThat(command.kind()).isEqualTo("LEGIT");

        PaymentEventCommand again = pacs008.toCommand(pacs008.toXml(command), NOW);
        assertThat(again.eventId()).isEqualTo(command.eventId());
        assertThat(again.authRef()).isEqualTo(command.authRef());
        assertThat(again.payerAccountId()).isEqualTo(command.payerAccountId());
        assertThat(again.payeeAccountId()).isEqualTo(command.payeeAccountId());
        assertThat(again.amount()).isEqualByComparingTo(command.amount());
        assertThat(again.currency()).isEqualTo(command.currency());
    }

    @Test
    void attestationRoundTripsInSupplementaryData() {
        PaymentEventCommand command = new PaymentEventCommand("EVT-SEMINAR-1", "AUTH-SEMINAR-1", PAYER, PAYEE,
                new BigDecimal("10.00"), "NGN", ISSUED, NOW, "LEGIT",
                "{\"version\":1,\"endToEndId\":\"AUTH-SEMINAR-1\"}");

        PaymentEventCommand again = pacs008.toCommand(pacs008.toXml(command), NOW);

        assertThat(again.attestationJson()).isEqualTo(command.attestationJson());
        assertThat(again.eventId()).isEqualTo(command.eventId());
    }

    @Test
    void messageIdIsTheEventIdOnlyWhenInstrIdIsAbsent() {
        String xml = pacs("""
                <PmtId><EndToEndId>AUTH-1</EndToEndId></PmtId>
                <IntrBkSttlmAmt Ccy="NGN">10.00</IntrBkSttlmAmt>
                <AccptncDtTm>2026-11-23T12:00:00Z</AccptncDtTm>
                %s
                """.formatted(accounts()));

        PaymentEventCommand command = pacs008.toCommand(xml, NOW);
        assertThat(command.eventId()).isEqualTo("MSG-ONLY");
    }

    @Test
    void samplePacs008WithoutAccountsIsNotAPaymentEvent() throws Exception {
        String sample = resource("/iso20022/pacs.008.001.07.xml");
        assertThatThrownBy(() -> pacs008.toCommand(sample, NOW))
                .isInstanceOf(IsoMappingException.class)
                .hasMessageContaining("DbtrAcct");
    }

    @Test
    void moreThanOneTransactionIsAMappingError() {
        String xml = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.07">
                  <FIToFICstmrCdtTrf>
                    <GrpHdr>
                      <MsgId>MSG</MsgId><CreDtTm>2026-11-23T12:00:00Z</CreDtTm><NbOfTxs>2</NbOfTxs>
                      <SttlmInf><SttlmMtd>INDA</SttlmMtd></SttlmInf>
                    </GrpHdr>
                    <CdtTrfTxInf>
                      <PmtId><InstrId>EVT-1</InstrId><EndToEndId>AUTH-1</EndToEndId></PmtId>
                      <IntrBkSttlmAmt Ccy="NGN">10.00</IntrBkSttlmAmt>
                    </CdtTrfTxInf>
                  </FIToFICstmrCdtTrf>
                </Document>
                """;
        assertThatThrownBy(() -> pacs008.toCommand(xml, NOW))
                .isInstanceOf(IsoMappingException.class)
                .hasMessageContaining("NbOfTxs");
    }

    @Test
    void pacs008WithoutEndToEndIdIsRejected() {
        String xml = pacs("""
                <PmtId><InstrId>EVT-1</InstrId></PmtId>
                <IntrBkSttlmAmt Ccy="NGN">10.00</IntrBkSttlmAmt>
                <AccptncDtTm>2026-11-23T12:00:00Z</AccptncDtTm>
                %s
                """.formatted(accounts()));
        assertThatThrownBy(() -> pacs008.toCommand(xml, NOW))
                .isInstanceOf(IsoMappingException.class)
                .hasMessageContaining("EndToEndId");
    }

    @Test
    void decisionRoundTripsAcceptedAndRejectedStatus() {
        Pacs002View accepted = pacs002.read(pacs002.toXml(
                Decision.of("EVT-1", "AUTH-1", CommitOutcome.COMMITTED, CommitReason.OK)));
        assertThat(accepted.transactionStatus()).isEqualTo("ACCP");
        assertThat(accepted.reason()).isEqualTo("OK");

        Pacs002View rejected = pacs002.read(pacs002.toXml(
                Decision.of("EVT-2", "AUTH-1", CommitOutcome.REJECTED, CommitReason.P4_PAYLOAD_MISMATCH)));
        assertThat(rejected.transactionStatus()).isEqualTo("RJCT");
        assertThat(rejected.reason()).isEqualTo("P4_PAYLOAD_MISMATCH");
    }

    private static String pacs(String transactionBody) {
        return """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.07">
                  <FIToFICstmrCdtTrf>
                    <GrpHdr>
                      <MsgId>MSG-ONLY</MsgId>
                      <CreDtTm>2026-11-23T11:00:00Z</CreDtTm>
                      <NbOfTxs>1</NbOfTxs>
                      <SttlmInf><SttlmMtd>INDA</SttlmMtd></SttlmInf>
                    </GrpHdr>
                    <CdtTrfTxInf>
                      %s
                    </CdtTrfTxInf>
                  </FIToFICstmrCdtTrf>
                </Document>
                """.formatted(transactionBody);
    }

    private static String accounts() {
        return """
                <DbtrAcct><Id><Othr><Id>%s</Id></Othr></Id></DbtrAcct>
                <CdtrAcct><Id><Othr><Id>%s</Id></Othr></Id></CdtrAcct>
                """.formatted(PAYER, PAYEE);
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = IsoMapperTest.class.getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
