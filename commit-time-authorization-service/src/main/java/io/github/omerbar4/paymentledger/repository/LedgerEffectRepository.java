package io.github.omerbar4.paymentledger.repository;

import io.github.omerbar4.paymentledger.domain.LedgerEffect;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEffectRepository extends JpaRepository<LedgerEffect, UUID> {

    boolean existsByEventId(String eventId);

    Optional<LedgerEffect> findByEventId(String eventId);
}
