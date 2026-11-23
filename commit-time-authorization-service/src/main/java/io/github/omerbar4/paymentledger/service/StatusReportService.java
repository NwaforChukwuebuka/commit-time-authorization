package io.github.omerbar4.paymentledger.service;

import io.github.omerbar4.paymentledger.domain.TransferCorrelation;
import io.github.omerbar4.paymentledger.exception.ApiException;
import io.github.omerbar4.paymentledger.iso.Pacs002Mapper;
import io.github.omerbar4.paymentledger.iso.Pacs002View;
import io.github.omerbar4.paymentledger.repository.TransferCorrelationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies Bank B's pacs.002 to the payer's transfer correlation. */
@Service
public class StatusReportService {

    private final Pacs002Mapper pacs002;
    private final TransferCorrelationRepository correlations;

    public StatusReportService(Pacs002Mapper pacs002, TransferCorrelationRepository correlations) {
        this.pacs002 = pacs002;
        this.correlations = correlations;
    }

    @Transactional
    public void apply(String xml) {
        Pacs002View report = pacs002.read(xml);
        if (report.originalEndToEndId() == null || report.originalEndToEndId().isBlank()) {
            throw ApiException.unprocessable("ISO_MAPPING", "pacs.002 is missing OrgnlEndToEndId");
        }
        TransferCorrelation transfer = correlations.findByEndToEndIdForUpdate(report.originalEndToEndId())
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND",
                        "Transfer " + report.originalEndToEndId() + " not found"));
        if ("ACCP".equals(report.transactionStatus())) {
            transfer.markCredited(report.originalInstructionId());
            return;
        }
        if (report.reason() != null && report.reason().startsWith("P1_")) {
            return;
        }
        transfer.markRejected();
    }
}
