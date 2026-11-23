package io.github.omerbar4.paymentledger.service;

import io.github.omerbar4.paymentledger.config.CtaProperties;
import io.github.omerbar4.paymentledger.domain.Account;
import io.github.omerbar4.paymentledger.domain.AuthGrant;
import io.github.omerbar4.paymentledger.domain.GrantStatus;
import io.github.omerbar4.paymentledger.dto.CreateGrantRequest;
import io.github.omerbar4.paymentledger.exception.ApiException;
import io.github.omerbar4.paymentledger.repository.AccountRepository;
import io.github.omerbar4.paymentledger.repository.AuthGrantRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GrantService {

    private final AuthGrantRepository grants;
    private final AccountRepository accounts;
    private final CtaProperties properties;

    public GrantService(AuthGrantRepository grants, AccountRepository accounts, CtaProperties properties) {
        this.grants = grants;
        this.accounts = accounts;
        this.properties = properties;
    }

    @Transactional
    public AuthGrant create(CreateGrantRequest request) {
        return open(request, null);
    }

    /**
     * @param authRef customer approval id from pain.001 {@code EndToEndId}. When blank, a new
     *                {@code AUTH-} id is generated, which is what {@code POST /api/v1/grants} does.
     */
    @Transactional
    public AuthGrant create(CreateGrantRequest request, String authRef) {
        return open(request, authRef);
    }

    private AuthGrant open(CreateGrantRequest request, String authRef) {
        if (request.payerAccountId().equals(request.payeeAccountId())) {
            throw ApiException.unprocessable("SAME_ACCOUNT", "Payer and payee must be different accounts");
        }
        Account payer = requireAccount(request.payerAccountId());
        if (!payer.getCurrency().equals(request.currency())) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH", "Grant currency must match the payer account");
        }
        UUID payeeId = request.payeeAccountId();
        if (!"PAYER".equals(properties.getRole())) {
            Account payee = requireAccount(payeeId);
            if (!payee.getCurrency().equals(request.currency())) {
                throw ApiException.unprocessable("CURRENCY_MISMATCH", "Grant currency must match both accounts");
            }
            payeeId = payee.getId();
        }
        String ref = authRef == null || authRef.isBlank() ? "AUTH-" + UUID.randomUUID() : authRef.trim();
        if (grants.existsById(ref)) {
            throw ApiException.conflict("GRANT_EXISTS", "Grant " + ref + " already exists");
        }
        return grants.save(AuthGrant.open(ref, payer.getId(), payeeId, request.amount(), request.currency()));
    }

    @Transactional
    public AuthGrant revoke(String authRef) {
        AuthGrant grant = grants.findById(authRef)
                .orElseThrow(() -> ApiException.notFound("GRANT_NOT_FOUND", "Grant " + authRef + " not found"));
        if (grant.getStatus() == GrantStatus.CONSUMED) {
            throw ApiException.conflict("GRANT_ALREADY_CONSUMED", "Grant " + authRef + " is already consumed");
        }
        grant.revoke();
        return grant;
    }

    private Account requireAccount(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account " + id + " not found"));
    }
}
