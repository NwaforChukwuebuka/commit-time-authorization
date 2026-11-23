package io.github.omerbar4.paymentledger.domain;

/** Reason stored on {@code decision.reason}. Names match the seminar prototype. */
public enum CommitReason {
    OK,
    P1_EVENT_ALREADY_COMMITTED,
    P2_STALE_EVENT,
    P3_NO_GRANT,
    P3_GRANT_CONSUMED,
    P3_GRANT_REVOKED,
    P4_PAYLOAD_MISMATCH,
    P5_ACCOUNT_NOT_ACTIVE,
    P6_INSUFFICIENT_FUNDS,
    P1_TRANSFER_ALREADY_CREDITED,
    P4_ATTESTATION_INVALID
}
