package io.github.omerbar4.paymentledger.service;

import io.github.omerbar4.paymentledger.domain.CorrelationState;
import io.github.omerbar4.paymentledger.domain.LedgerEffect;
import io.github.omerbar4.paymentledger.domain.TransferCorrelation;
import io.github.omerbar4.paymentledger.exception.ApiException;
import io.github.omerbar4.paymentledger.repository.LedgerEffectRepository;
import io.github.omerbar4.paymentledger.repository.TransferCorrelationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compensates a debit whose credit was rejected. A second call sees {@code REVERSED} and does not
 * post again.
 */
@Service
public class ReversalService {

    private final TransferCorrelationRepository correlations;
    private final LedgerEffectRepository ledger;
    private final TransactionService transactions;

    public ReversalService(TransferCorrelationRepository correlations, LedgerEffectRepository ledger,
                           TransactionService transactions) {
        this.correlations = correlations;
        this.ledger = ledger;
        this.transactions = transactions;
    }

    @Transactional
    public void reverse(String endToEndId) {
        TransferCorrelation transfer = correlations.findByEndToEndIdForUpdate(endToEndId)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND", "Transfer " + endToEndId + " not found"));
        if (transfer.getState() == CorrelationState.REVERSED) {
            return;
        }
        if (transfer.getState() != CorrelationState.REJECTED) {
            throw ApiException.conflict("TRANSFER_NOT_REVERSIBLE",
                    "Transfer " + endToEndId + " is " + transfer.getState());
        }
        LedgerEffect effect = ledger.findByEventId(transfer.getDebitEventId())
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND",
                        "Debit for " + endToEndId + " has no ledger row"));
        transactions.refund("cta-reverse:" + endToEndId, effect.getTransactionId());
        transfer.markReversed();
    }
}
