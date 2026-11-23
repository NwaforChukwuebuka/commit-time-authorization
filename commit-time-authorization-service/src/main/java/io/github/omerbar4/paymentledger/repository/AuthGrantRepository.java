package io.github.omerbar4.paymentledger.repository;

import io.github.omerbar4.paymentledger.domain.AuthGrant;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthGrantRepository extends JpaRepository<AuthGrant, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from AuthGrant g where g.authRef = :authRef")
    Optional<AuthGrant> findByAuthRefForUpdate(@Param("authRef") String authRef);
}
