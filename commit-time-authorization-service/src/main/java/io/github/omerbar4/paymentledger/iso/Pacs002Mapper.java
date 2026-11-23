package io.github.omerbar4.paymentledger.iso;

import com.prowidesoftware.swift.model.mx.MxPacs00200110;
import com.prowidesoftware.swift.model.mx.dic.FIToFIPaymentStatusReportV10;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader91;
import com.prowidesoftware.swift.model.mx.dic.PaymentTransaction110;
import com.prowidesoftware.swift.model.mx.dic.StatusReason6Choice;
import com.prowidesoftware.swift.model.mx.dic.StatusReasonInformation12;
import io.github.omerbar4.paymentledger.domain.CommitOutcome;
import io.github.omerbar4.paymentledger.domain.Decision;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;

/** A stored decision rendered as pacs.002.001.10, and read back for tests. */
@Component
public class Pacs002Mapper {

    public MxPacs00200110 fromDecision(Decision decision) {
        PaymentTransaction110 transaction = new PaymentTransaction110()
                .setOrgnlInstrId(decision.getEventId())
                .setOrgnlEndToEndId(decision.getAuthRef())
                .setTxSts(status(decision.getOutcome()))
                .addStsRsnInf(new StatusReasonInformation12()
                        .setRsn(new StatusReason6Choice().setPrtry(decision.getReason().name())));
        GroupHeader91 header = new GroupHeader91()
                .setMsgId(decision.getId().toString())
                .setCreDtTm(decision.getCreatedAt().atOffset(ZoneOffset.UTC));
        return new MxPacs00200110().setFIToFIPmtStsRpt(new FIToFIPaymentStatusReportV10()
                .setGrpHdr(header)
                .addTxInfAndSts(transaction));
    }

    public String toXml(Decision decision) {
        return fromDecision(decision).message();
    }

    public Pacs002View read(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IsoMappingException("pacs.002 document is empty");
        }
        MxPacs00200110 message;
        try {
            message = MxPacs00200110.parse(xml);
        } catch (RuntimeException ex) {
            throw new IsoMappingException("pacs.002 document could not be parsed");
        }
        if (message == null || message.getFIToFIPmtStsRpt() == null) {
            throw new IsoMappingException("document is not pacs.002.001.10");
        }
        List<PaymentTransaction110> transactions = message.getFIToFIPmtStsRpt().getTxInfAndSts();
        IsoMessages.requireOne(transactions, "pacs.002");
        PaymentTransaction110 transaction = transactions.get(0);
        String reason = null;
        if (transaction.getStsRsnInf() != null && !transaction.getStsRsnInf().isEmpty()
                && transaction.getStsRsnInf().get(0).getRsn() != null) {
            reason = transaction.getStsRsnInf().get(0).getRsn().getPrtry();
        }
        return new Pacs002View(transaction.getTxSts(), reason, transaction.getOrgnlInstrId(),
                transaction.getOrgnlEndToEndId());
    }

    static String status(CommitOutcome outcome) {
        return switch (outcome) {
            case COMMITTED -> "ACCP";
            case DUPLICATE, REJECTED, DECLINED -> "RJCT";
        };
    }
}
