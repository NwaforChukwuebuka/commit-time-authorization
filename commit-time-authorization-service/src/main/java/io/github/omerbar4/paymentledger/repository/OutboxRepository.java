package io.github.omerbar4.paymentledger.repository;

import io.github.omerbar4.paymentledger.domain.OutboxMessage;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    List<OutboxMessage> findTop20ByPublishedAtIsNullOrderByCreatedAtAsc();
}
