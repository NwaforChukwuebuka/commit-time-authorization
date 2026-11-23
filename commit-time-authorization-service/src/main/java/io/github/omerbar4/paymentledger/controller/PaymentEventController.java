package io.github.omerbar4.paymentledger.controller;

import io.github.omerbar4.paymentledger.dto.PaymentEventAccepted;
import io.github.omerbar4.paymentledger.dto.PaymentEventRequest;
import io.github.omerbar4.paymentledger.service.IngressQueue;
import io.github.omerbar4.paymentledger.service.PaymentEventCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment-events")
@Tag(name = "Payment events", description = "Enqueue a payment. Authorization happens at commit, not here.")
public class PaymentEventController {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventController.class);

    private final IngressQueue queue;

    public PaymentEventController(IngressQueue queue) {
        this.queue = queue;
    }

    @PostMapping
    @Operation(summary = "Enqueue a payment event",
            description = "Checks the shape of the event and enqueues it. Does not authorize the payment and "
                    + "does not post to the ledger. The Idempotency-Key header, when supplied, is transport "
                    + "metadata only. It does not authorize the payment and it does not replace the grant. "
                    + "A second delivery of the same event is decided at commit.")
    public ResponseEntity<PaymentEventAccepted> enqueue(
            @Parameter(description = "Transport message id only. Not customer authorization and not a substitute for the grant.",
                    required = false)
            @RequestHeader(value = TransactionController.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody PaymentEventRequest request) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            log.debug("Idempotency-Key accepted as transport metadata and ignored for authorization");
        }
        String kind = request.kind() == null || request.kind().isBlank() ? "LEGIT" : request.kind();
        queue.publish(new PaymentEventCommand(request.eventId(), request.authRef(), request.payerAccountId(),
                request.payeeAccountId(), request.amount(), request.currency(), request.issuedAt(), Instant.now(),
                kind));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new PaymentEventAccepted(request.eventId()));
    }
}
