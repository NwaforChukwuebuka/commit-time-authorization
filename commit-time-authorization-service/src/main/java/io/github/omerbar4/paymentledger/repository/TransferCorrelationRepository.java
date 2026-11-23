package io.github.omerbar4.paymentledger.repository;

import io.github.omerbar4.paymentledger.domain.TransferCorrelation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferCorrelationRepository
        extends JpaRepository<TransferCorrelation, String>, TransferCorrelationInsert {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TransferCorrelation c where c.endToEndId = :endToEndId")
    Optional<TransferCorrelation> findByEndToEndIdForUpdate(@Param("endToEndId") String endToEndId);
}
