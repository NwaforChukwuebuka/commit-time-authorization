package io.github.omerbar4.paymentledger.repository;

import io.github.omerbar4.paymentledger.domain.Institution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstitutionRepository extends JpaRepository<Institution, String> {
}
