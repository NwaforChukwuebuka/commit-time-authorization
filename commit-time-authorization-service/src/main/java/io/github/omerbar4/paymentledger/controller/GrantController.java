package io.github.omerbar4.paymentledger.controller;

import io.github.omerbar4.paymentledger.domain.AuthGrant;
import io.github.omerbar4.paymentledger.dto.CreateGrantRequest;
import io.github.omerbar4.paymentledger.dto.GrantResponse;
import io.github.omerbar4.paymentledger.service.GrantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/grants")
@Tag(name = "Grants", description = "Single-use customer authorization consumed at commit")
public class GrantController {

    private final GrantService grants;

    public GrantController(GrantService grants) {
        this.grants = grants;
    }

    @PostMapping
    @Operation(summary = "Issue a grant",
            description = "Records an OPEN customer approval. The payload hash is computed here. "
                    + "The grant is consumed only inside the commit transaction.")
    public ResponseEntity<GrantResponse> create(@Valid @RequestBody CreateGrantRequest request) {
        AuthGrant grant = grants.create(request);
        return ResponseEntity.created(URI.create("/api/v1/grants/" + grant.getAuthRef()))
                .body(GrantResponse.from(grant));
    }

    @DeleteMapping("/{authRef}")
    @Operation(summary = "Revoke a grant", description = "Sets the grant to REVOKED. A consumed grant cannot be revoked.")
    public GrantResponse revoke(@PathVariable String authRef) {
        return GrantResponse.from(grants.revoke(authRef));
    }
}
