package io.github.omerbar4.paymentledger.repository;

import io.github.omerbar4.paymentledger.domain.Decision;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionRepository extends JpaRepository<Decision, UUID> {

    List<Decision> findByEventIdOrderByCreatedAtAsc(String eventId);
}
