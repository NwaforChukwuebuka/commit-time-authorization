package io.github.omerbar4.paymentledger.controller;

import io.github.omerbar4.paymentledger.domain.Decision;
import io.github.omerbar4.paymentledger.dto.DecisionResponse;
import io.github.omerbar4.paymentledger.exception.ApiException;
import io.github.omerbar4.paymentledger.iso.Pacs002Mapper;
import io.github.omerbar4.paymentledger.repository.DecisionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/decisions")
@Tag(name = "Decisions", description = "Commit outcomes recorded for a payment event")
public class DecisionController {

    private final DecisionRepository decisions;
    private final Pacs002Mapper pacs002;

    public DecisionController(DecisionRepository decisions, Pacs002Mapper pacs002) {
        this.decisions = decisions;
        this.pacs002 = pacs002;
    }

    @GetMapping
    @Operation(summary = "List decisions for an event",
            description = "Each commit attempt appends a row. A replay does not overwrite the first decision. "
                    + "Accept application/xml returns one pacs.002.001.10 for the latest decision. "
                    + "Any other Accept value, including */*, returns the JSON list.")
    public ResponseEntity<?> list(@RequestParam String eventId, HttpServletRequest request) {
        List<Decision> rows = decisions.findByEventIdOrderByCreatedAtAsc(eventId);
        if (!wantsXml(request)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(rows.stream().map(DecisionResponse::from).toList());
        }
        if (rows.isEmpty()) {
            throw ApiException.notFound("DECISION_NOT_FOUND", "No decision for event " + eventId);
        }
        Decision latest = rows.get(rows.size() - 1);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(pacs002.toXml(latest));
    }

    /** application/xml wins only when it outranks application/json. A bare wildcard stays JSON. */
    static boolean wantsXml(HttpServletRequest request) {
        List<MediaType> accepted;
        try {
            accepted = MediaType.parseMediaTypes(request.getHeader(HttpHeaders.ACCEPT));
        } catch (InvalidMediaTypeException ex) {
            return false;
        }
        MediaType.sortBySpecificityAndQuality(accepted);
        for (MediaType type : accepted) {
            if (type.isWildcardType() && type.isWildcardSubtype()) {
                return false;
            }
            if (isXml(type)) {
                return true;
            }
            if (isJson(type)) {
                return false;
            }
        }
        return false;
    }

    private static boolean isXml(MediaType type) {
        return "xml".equals(type.getSubtype()) || type.getSubtype().endsWith("+xml");
    }

    private static boolean isJson(MediaType type) {
        return "json".equals(type.getSubtype()) || type.getSubtype().endsWith("+json");
    }
}
