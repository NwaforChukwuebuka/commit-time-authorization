package io.github.omerbar4.paymentledger.controller;

import io.github.omerbar4.paymentledger.domain.AuthGrant;
import io.github.omerbar4.paymentledger.dto.GrantResponse;
import io.github.omerbar4.paymentledger.dto.PaymentEventAccepted;
import io.github.omerbar4.paymentledger.iso.Pain001Grant;
import io.github.omerbar4.paymentledger.iso.Pain001Mapper;
import io.github.omerbar4.paymentledger.iso.Pacs008Mapper;
import io.github.omerbar4.paymentledger.service.GrantService;
import io.github.omerbar4.paymentledger.service.IngressQueue;
import io.github.omerbar4.paymentledger.service.PaymentEventCommand;
import io.github.omerbar4.paymentledger.service.StatusReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ISO ingress. Parses the message and either issues a grant or enqueues a payment event.
 * Does not call {@code commitIntraBank}.
 */
@RestController
@RequestMapping("/api/v1/iso")
@Tag(name = "ISO 20022", description = "pain.001 issues a grant. pacs.008 enqueues a payment event.")
public class IsoIngressController {

    private final Pain001Mapper pain001;
    private final Pacs008Mapper pacs008;
    private final GrantService grants;
    private final IngressQueue queue;
    private final StatusReportService statusReports;

    public IsoIngressController(Pain001Mapper pain001, Pacs008Mapper pacs008, GrantService grants, IngressQueue queue,
                                StatusReportService statusReports) {
        this.pain001 = pain001;
        this.pacs008 = pacs008;
        this.grants = grants;
        this.queue = queue;
        this.statusReports = statusReports;
    }

    @PostMapping(value = "/pain.001", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Issue a grant from pain.001",
            description = "Reads one credit transfer. DbtrAcct and CdtrAcct Othr/Id are ledger account ids. "
                    + "EndToEndId, when present, is the grant id. The response is the grant, as JSON. "
                    + "The message is not an authorization decision.")
    public ResponseEntity<GrantResponse> pain001(@RequestBody String xml) {
        Pain001Grant mapped = pain001.toGrant(xml);
        AuthGrant grant = grants.create(mapped.request(), mapped.authRef());
        return ResponseEntity.created(URI.create("/api/v1/grants/" + grant.getAuthRef()))
                .body(GrantResponse.from(grant));
    }

    @PostMapping(value = "/pacs.008", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Enqueue a payment event from pacs.008",
            description = "InstrId is the event id. MsgId is used only when InstrId is absent, and is not "
                    + "customer authorization. EndToEndId is the grant. Returns 202 and does not post.")
    public ResponseEntity<PaymentEventAccepted> pacs008(@RequestBody String xml) {
        PaymentEventCommand command = pacs008.toCommand(xml, Instant.now());
        queue.publish(command);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new PaymentEventAccepted(command.eventId()));
    }

    @PostMapping(value = "/pacs.002", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    @Operation(summary = "Apply a pacs.002 status report",
            description = "Bank A records RJCT on the transfer correlation. A duplicate reason (P1) does not "
                    + "change a debit that already happened. ACCP marks the transfer credited.")
    public ResponseEntity<Void> pacs002(@RequestBody String xml) {
        statusReports.apply(xml);
        return ResponseEntity.noContent().build();
    }
}
