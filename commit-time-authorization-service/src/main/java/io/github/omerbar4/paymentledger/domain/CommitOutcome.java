package io.github.omerbar4.paymentledger.domain;

public enum CommitOutcome {
    COMMITTED,
    DUPLICATE,
    REJECTED,
    DECLINED
}
