package io.github.omerbar4.paymentledger.iso;

import com.prowidesoftware.swift.model.mx.MxPacs00800107;
import com.prowidesoftware.swift.model.mx.dic.ActiveCurrencyAndAmount;
import com.prowidesoftware.swift.model.mx.dic.CreditTransferTransaction30;
import com.prowidesoftware.swift.model.mx.dic.FIToFICustomerCreditTransferV07;
import com.prowidesoftware.swift.model.mx.dic.GroupHeader70;
import com.prowidesoftware.swift.model.mx.dic.PaymentIdentification3;
import com.prowidesoftware.swift.model.mx.dic.SettlementInstruction4;
import com.prowidesoftware.swift.model.mx.dic.SettlementMethod1Code;
import io.github.omerbar4.paymentledger.service.PaymentEventCommand;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * pacs.008.001.07 to and from a payment event.
 *
 * <p>{@code PmtId/InstrId} is the event id. {@code GrpHdr/MsgId} is only the fallback when
 * {@code InstrId} is absent, so a new message id alone is not a new payment. {@code EndToEndId}
 * is the grant. {@code now} is supplied by the caller and is not read from the XML.
 */
@Component
public class Pacs008Mapper {

    public PaymentEventCommand toCommand(String xml, Instant now) {
        if (xml == null || xml.isBlank()) {
            throw new IsoMappingException("pacs.008 document is empty");
        }
        MxPacs00800107 message;
        try {
            message = MxPacs00800107.parse(xml);
        } catch (RuntimeException ex) {
            throw new IsoMappingException("pacs.008 document could not be parsed");
        }
        if (message == null || message.getFIToFICstmrCdtTrf() == null) {
            throw new IsoMappingException("document is not pacs.008.001.07");
        }
        PaymentEventCommand command = toCommand(message, now);
        if (command.attestationJson() != null) {
            return command;
        }
        String attestation = AttestationXml.fromXml(xml);
        return attestation == null ? command : command.withAttestation(attestation);
    }

    public PaymentEventCommand toCommand(MxPacs00800107 message, Instant now) {
        FIToFICustomerCreditTransferV07 transfer = message.getFIToFICstmrCdtTrf();
        if (transfer == null) {
            throw new IsoMappingException("document is not pacs.008.001.07");
        }
        GroupHeader70 header = transfer.getGrpHdr();
        IsoMessages.requireSingleCount(header == null ? null : header.getNbOfTxs(), "pacs.008 GrpHdr");
        IsoMessages.requireOne(transfer.getCdtTrfTxInf(), "pacs.008");

        CreditTransferTransaction30 tx = transfer.getCdtTrfTxInf().get(0);
        PaymentIdentification3 paymentId = tx.getPmtId();
        String instrId = paymentId == null ? null : IsoMessages.blankToNull(paymentId.getInstrId());
        String msgId = header == null ? null : IsoMessages.blankToNull(header.getMsgId());
        String eventId = instrId != null ? instrId : msgId;
        if (eventId == null) {
            throw new IsoMappingException("PmtId/InstrId or GrpHdr/MsgId is required");
        }
        String authRef = paymentId == null ? null : IsoMessages.blankToNull(paymentId.getEndToEndId());
        if (authRef == null) {
            throw new IsoMappingException("PmtId/EndToEndId is required");
        }
        if (tx.getDbtrAcct() == null || tx.getCdtrAcct() == null || tx.getIntrBkSttlmAmt() == null) {
            throw new IsoMappingException("pacs.008 requires DbtrAcct, CdtrAcct, and IntrBkSttlmAmt");
        }
        Instant issuedAt = tx.getAccptncDtTm() != null
                ? tx.getAccptncDtTm().toInstant()
                : (header != null && header.getCreDtTm() != null ? header.getCreDtTm().toInstant() : null);
        if (issuedAt == null) {
            throw new IsoMappingException("AccptncDtTm or GrpHdr/CreDtTm is required");
        }
        ActiveCurrencyAndAmount amount = tx.getIntrBkSttlmAmt();
        return new PaymentEventCommand(eventId, authRef,
                IsoAccounts.require(tx.getDbtrAcct().getId(), "DbtrAcct/Id/Othr/Id"),
                IsoAccounts.require(tx.getCdtrAcct().getId(), "CdtrAcct/Id/Othr/Id"),
                IsoAmounts.require(amount.getValue(), "IntrBkSttlmAmt"),
                IsoAmounts.currency(amount.getCcy(), "IntrBkSttlmAmt"),
                issuedAt, now, "LEGIT", AttestationXml.fromTransaction(tx));
    }

    public MxPacs00800107 toMessage(PaymentEventCommand command) {
        CreditTransferTransaction30 tx = new CreditTransferTransaction30()
                .setPmtId(new PaymentIdentification3()
                        .setInstrId(command.eventId())
                        .setEndToEndId(command.authRef()))
                .setIntrBkSttlmAmt(new ActiveCurrencyAndAmount()
                        .setValue(command.amount())
                        .setCcy(command.currency()))
                .setAccptncDtTm(command.issuedAt().atOffset(ZoneOffset.UTC))
                .setDbtrAcct(IsoAccounts.cash24(command.payerAccountId()))
                .setCdtrAcct(IsoAccounts.cash24(command.payeeAccountId()));
        AttestationXml.embed(tx, command.attestationJson());
        Instant created = command.now() != null ? command.now() : command.issuedAt();
        GroupHeader70 header = new GroupHeader70()
                .setMsgId(command.eventId())
                .setCreDtTm(created.atOffset(ZoneOffset.UTC))
                .setNbOfTxs("1")
                .setSttlmInf(new SettlementInstruction4().setSttlmMtd(SettlementMethod1Code.INDA));
        return new MxPacs00800107().setFIToFICstmrCdtTrf(new FIToFICustomerCreditTransferV07()
                .setGrpHdr(header)
                .addCdtTrfTxInf(tx));
    }

    public String toXml(PaymentEventCommand command) {
        return toMessage(command).message();
    }
}
