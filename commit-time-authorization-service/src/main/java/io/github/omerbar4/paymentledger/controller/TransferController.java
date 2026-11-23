package io.github.omerbar4.paymentledger.controller;

import io.github.omerbar4.paymentledger.service.ReversalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Inter-bank exception handling")
public class TransferController {

    private final ReversalService reversals;

    public TransferController(ReversalService reversals) {
        this.reversals = reversals;
    }

    @PostMapping("/{endToEndId}/reverse")
    @Operation(summary = "Reverse a rejected debit",
            description = "Stub for a credit that Bank B refused. Posts one compensating refund of the debit "
                    + "and sets the transfer to REVERSED. A second call does not post again.")
    public ResponseEntity<Void> reverse(@PathVariable String endToEndId) {
        reversals.reverse(endToEndId);
        return ResponseEntity.noContent().build();
    }
}
