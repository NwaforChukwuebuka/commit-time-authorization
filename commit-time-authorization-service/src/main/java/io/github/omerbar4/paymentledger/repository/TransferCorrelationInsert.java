package io.github.omerbar4.paymentledger.repository;

import io.github.omerbar4.paymentledger.domain.TransferCorrelation;

interface TransferCorrelationInsert {

    /** {@code true} when this transaction claimed {@code end_to_end_id}. */
    boolean insertIfAbsent(TransferCorrelation correlation);
}
