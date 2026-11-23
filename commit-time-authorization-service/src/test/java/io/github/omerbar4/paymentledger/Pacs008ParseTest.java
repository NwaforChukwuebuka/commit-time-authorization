package io.github.omerbar4.paymentledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.prowidesoftware.swift.model.mx.MxPacs00800107;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Parses the copied Prowide sample. That tree is not a Maven module. */
class Pacs008ParseTest {

    @Test
    void parsesSampleAmountDebtorCreditorAndEndToEndId() throws Exception {
        String xml;
        try (InputStream in = Pacs008ParseTest.class.getResourceAsStream("/iso20022/pacs.008.001.07.xml")) {
            assertThat(in).as("sample pacs.008 copied from prowide-iso20022-examples").isNotNull();
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        MxPacs00800107 message = MxPacs00800107.parse(xml);
        var transfer = message.getFIToFICstmrCdtTrf().getCdtTrfTxInf().get(0);

        assertThat(transfer.getIntrBkSttlmAmt().getValue()).isEqualByComparingTo("5000");
        assertThat(transfer.getIntrBkSttlmAmt().getCcy()).isEqualTo("USD");
        assertThat(transfer.getDbtr().getNm()).isEqualTo("Jhon Doe");
        assertThat(transfer.getCdtr().getNm()).isEqualTo("Mike Smith");
        assertThat(transfer.getPmtId().getEndToEndId()).isEqualTo("987654321987654321");
    }
}
