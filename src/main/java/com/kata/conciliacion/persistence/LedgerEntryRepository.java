package com.kata.conciliacion.persistence;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, Long> {

    // Query derivada del nombre: WHERE processor = ? AND settlement_date = ?
    // La cubre el índice ix_ledger_entry_processor_date.
    List<LedgerEntryEntity> findByProcessorAndSettlementDate(String processor, LocalDate settlementDate);
}
