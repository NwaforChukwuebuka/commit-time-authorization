package io.github.omerbar4.paymentledger.iso;

import com.prowidesoftware.swift.model.mx.MxPain00100111;
import com.prowidesoftware.swift.model.mx.dic.CreditTransferTransaction54;
import com.prowidesoftware.swift.model.mx.dic.CustomerCreditTransferInitiationV11;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader95;
import com.prowidesoftware.swift.model.mx.dic.PaymentInstruction40;
import io.github.omerbar4.paymentledger.dto.CreateGrantRequest;
import org.springframework.stereotype.Component;

/** pain.001.001.11 to a grant request. One payment instruction, one credit transfer. */
@Component
public class Pain001Mapper {

    public Pain001Grant toGrant(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IsoMappingException("pain.001 document is empty");
        }
        MxPain00100111 message;
        try {
            message = MxPain00100111.parse(xml);
        } catch (RuntimeException ex) {
            throw new IsoMappingException("pain.001 document could not be parsed");
        }
        CustomerCreditTransferInitiationV11 initiation = message == null ? null : message.getCstmrCdtTrfInitn();
        if (initiation == null) {
            throw new IsoMappingException("document is not pain.001.001.11");
        }
        GroupHeader95 header = initiation.getGrpHdr();
        IsoMessages.requireSingleCount(header == null ? null : header.getNbOfTxs(), "pain.001 GrpHdr");
        IsoMessages.requireOne(initiation.getPmtInf(), "pain.001");

        PaymentInstruction40 payment = initiation.getPmtInf().get(0);
        IsoMessages.requireSingleCount(payment.getNbOfTxs(), "pain.001 PmtInf");
        IsoMessages.requireOne(payment.getCdtTrfTxInf(), "pain.001");

        CreditTransferTransaction54 transfer = payment.getCdtTrfTxInf().get(0);
        if (payment.getDbtrAcct() == null || transfer.getCdtrAcct() == null || transfer.getAmt() == null
                || transfer.getAmt().getInstdAmt() == null) {
            throw new IsoMappingException("pain.001 requires DbtrAcct, CdtrAcct, and InstdAmt");
        }
        var amount = transfer.getAmt().getInstdAmt();
        CreateGrantRequest request = new CreateGrantRequest(
                IsoAccounts.require(payment.getDbtrAcct().getId(), "DbtrAcct/Id/Othr/Id"),
                IsoAccounts.require(transfer.getCdtrAcct().getId(), "CdtrAcct/Id/Othr/Id"),
                IsoAmounts.require(amount.getValue(), "InstdAmt"),
                IsoAmounts.currency(amount.getCcy(), "InstdAmt"));
        String authRef = transfer.getPmtId() == null ? null : IsoMessages.blankToNull(transfer.getPmtId().getEndToEndId());
        return new Pain001Grant(request, authRef);
    }
}
