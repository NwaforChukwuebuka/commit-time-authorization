package io.github.omerbar4.paymentledger.config;

import io.github.omerbar4.paymentledger.domain.Account;
import io.github.omerbar4.paymentledger.domain.AttestationKeys;
import io.github.omerbar4.paymentledger.domain.Institution;
import io.github.omerbar4.paymentledger.domain.PemSources;
import io.github.omerbar4.paymentledger.repository.AccountRepository;
import io.github.omerbar4.paymentledger.repository.InstitutionRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payer and payee processes each own a settlement account and a copy of the payer public key.
 * Intra-bank does not create either.
 */
@Component
public class InstitutionBootstrap implements ApplicationRunner {

    private final CtaProperties properties;
    private final InstitutionRepository institutions;
    private final AccountRepository accounts;

    public InstitutionBootstrap(CtaProperties properties, InstitutionRepository institutions, AccountRepository accounts) {
        this.properties = properties;
        this.institutions = institutions;
        this.accounts = accounts;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String role = properties.getRole();
        if (role == null || "INTRA".equals(role)) {
            return;
        }
        String publicPem = PemSources.resolve(properties.getAttestation().getPayerPublicKeyPem());
        if (publicPem == null || publicPem.isBlank()) {
            throw new IllegalStateException("cta.attestation.payer-public-key-pem is required when cta.role is " + role);
        }
        if ("PAYER".equals(role)) {
            String privatePem = PemSources.resolve(properties.getAttestation().getPrivateKeyPem());
            if (privatePem == null || privatePem.isBlank()) {
                throw new IllegalStateException("cta.attestation.private-key-pem is required when cta.role=PAYER");
            }
            AttestationKeys.requirePair(privatePem, publicPem);
            if (properties.getCounterpartyInstitutionId() == null || properties.getCounterpartyInstitutionId().isBlank()) {
                throw new IllegalStateException("cta.counterparty-institution-id is required when cta.role=PAYER");
            }
        } else if (!"PAYEE".equals(role)) {
            throw new IllegalStateException("Unknown cta.role " + role);
        }
        AttestationKeys.readPublic(publicPem);

        Institution institution = institutions.findById(properties.getInstitutionId()).orElse(null);
        if (institution == null) {
            Account settlement = accounts.save(new Account("settlement-" + properties.getInstitutionId(), "NGN", true));
            institutions.save(Institution.create(properties.getInstitutionId(), publicPem.trim(), settlement.getId()));
        } else {
            institution.replacePublicKey(publicPem.trim());
        }
    }
}
