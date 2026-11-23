package io.github.omerbar4.paymentledger.service;

import io.github.omerbar4.paymentledger.domain.CommitOutcome;
import io.github.omerbar4.paymentledger.domain.CommitReason;

public record CommitResult(CommitOutcome outcome, CommitReason reason) {
}
