package io.github.omerbar4.paymentledger.dto;

import io.github.omerbar4.paymentledger.domain.CommitOutcome;
import io.github.omerbar4.paymentledger.domain.CommitReason;
import io.github.omerbar4.paymentledger.domain.Decision;
import java.time.Instant;
import java.util.UUID;

public record DecisionResponse(
        UUID id,
        String eventId,
        String authRef,
        CommitOutcome outcome,
        CommitReason reason,
        Instant createdAt) {

    public static DecisionResponse from(Decision decision) {
        return new DecisionResponse(decision.getId(), decision.getEventId(), decision.getAuthRef(),
                decision.getOutcome(), decision.getReason(), decision.getCreatedAt());
    }
}
