package io.github.omerbar4.paymentledger.iso;

import io.github.omerbar4.paymentledger.dto.CreateGrantRequest;

/**
 * A pain.001 reduced to a grant. {@code authRef} is {@code PmtId/EndToEndId} when the message
 * carries one, and null when the service should assign an id.
 */
public record Pain001Grant(CreateGrantRequest request, String authRef) {
}
