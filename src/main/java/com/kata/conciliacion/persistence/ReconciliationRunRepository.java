package com.kata.conciliacion.persistence;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRunEntity, Long> {

    Optional<ReconciliationRunEntity> findByProcessorAndSettlementDateAndFileHashAndStatus(
            String processor, LocalDate settlementDate, String fileHash, RunStatus status);
}
