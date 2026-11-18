package com.kata.conciliacion.service;

import com.kata.conciliacion.domain.LedgerEntry;
import com.kata.conciliacion.domain.ReconciliationResult;
import com.kata.conciliacion.domain.SettlementLine;
import com.kata.conciliacion.engine.ReconciliationEngine;
import com.kata.conciliacion.engine.ReconciliationSummary;
import com.kata.conciliacion.persistence.LedgerEntryEntity;
import com.kata.conciliacion.persistence.LedgerEntryRepository;
import com.kata.conciliacion.persistence.ReconciliationResultEntity;
import com.kata.conciliacion.persistence.ReconciliationResultRepository;
import com.kata.conciliacion.persistence.ReconciliationRunEntity;
import com.kata.conciliacion.persistence.ReconciliationRunRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquesta: carga (IO) → motor (puro) → guarda (IO). "Functional core, imperative shell":
 * la lógica está en el motor; acá solo hay entrada/salida y la frontera transaccional.
 */
@Service
public class ReconciliationService {

    private final LedgerEntryRepository ledgerRepository;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationResultRepository resultRepository;
    private final ReconciliationEngine engine;
    private final Clock clock;

    // Inyección por constructor: dependencias final, explícitas y testeables sin Spring.
    public ReconciliationService(LedgerEntryRepository ledgerRepository,
                                 ReconciliationRunRepository runRepository,
                                 ReconciliationResultRepository resultRepository,
                                 ReconciliationEngine engine,
                                 Clock clock) {
        this.ledgerRepository = ledgerRepository;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.engine = engine;
        this.clock = clock;
    }

    /**
     * Todo o nada: la run, sus resultados y el paso a COMPLETED se confirman en un solo commit.
     * Si algo falla, rollback y no queda una run a medias con la mitad de los resultados.
     */
    @Transactional
    public RunOutcome reconcile(String processor, LocalDate settlementDate, List<SettlementLine> lines) {
        ReconciliationRunEntity run = runRepository.save(
                ReconciliationRunEntity.start(processor, settlementDate, clock.instant()));

        List<LedgerEntry> ledger = ledgerRepository
                .findByProcessorAndSettlementDate(processor, settlementDate).stream()
                .map(LedgerEntryEntity::toDomain)
                .toList();

        List<ReconciliationResult> results = engine.reconcile(ledger, lines);

        resultRepository.saveAll(results.stream()
                .map(result -> ReconciliationResultEntity.of(result, run))
                .toList());

        // Sin save(): la run está "managed" en el contexto de persistencia; Hibernate detecta
        // el cambio (dirty checking) y emite el UPDATE en el flush previo al commit.
        run.complete(clock.instant());

        return new RunOutcome(run.getId(), ReconciliationSummary.of(results));
    }
}
