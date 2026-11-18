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
import com.kata.conciliacion.persistence.ResultTypeCount;
import com.kata.conciliacion.persistence.RunStatus;
import java.time.Clock;
import java.time.LocalDate;
import com.kata.conciliacion.domain.ReconciliationResult.Matched;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final FailedRunRecorder failedRunRecorder;
    private final Clock clock;

    // Inyección por constructor: dependencias final, explícitas y testeables sin Spring.
    public ReconciliationService(LedgerEntryRepository ledgerRepository,
                                 ReconciliationRunRepository runRepository,
                                 ReconciliationResultRepository resultRepository,
                                 ReconciliationEngine engine,
                                 FailedRunRecorder failedRunRecorder,
                                 Clock clock) {
        this.ledgerRepository = ledgerRepository;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.engine = engine;
        this.failedRunRecorder = failedRunRecorder;
        this.clock = clock;
    }

    /**
     * Todo o nada: la run, sus resultados y el paso a COMPLETED se confirman en un solo commit.
     * Si algo falla, rollback y no queda una run a medias con la mitad de los resultados; lo
     * único que sobrevive es el registro de la run FAILED (REQUIRES_NEW en otro bean).
     *
     * <p>Idempotente por (processor, settlementDate, fileHash): si ya hay una run COMPLETED con
     * esa clave, la devuelve sin reprocesar. Si otra ejecución concurrente gana la carrera, el
     * INSERT de la run tira violación de unicidad (la maneja ReconciliationFacade).
     */
    @Transactional
    public RunOutcome reconcile(String processor, LocalDate settlementDate, String fileHash,
                                List<SettlementLine> lines) {
        // 1) Fast path: evita reprocesar en el caso común (reintento secuencial). NO alcanza
        //    para la concurrencia: dos ejecuciones pueden pasar este chequeo a la vez.
        Optional<RunOutcome> existing = lookupCompleted(processor, settlementDate, fileHash);
        if (existing.isPresent()) {
            return existing.get();
        }

        // 2) Reclamar la clave. saveAndFlush y no save: con SEQUENCE el INSERT quedaría en
        //    memoria hasta el commit, y el perdedor procesaría el archivo entero antes de
        //    enterarse de que perdió. Con flush, el índice único decide ACÁ.
        //    Si otra tx insertó la misma clave y no commiteó todavía, Postgres nos BLOQUEA en
        //    esta línea hasta que la otra termine: commit → violación; rollback → seguimos.
        //    Queda fuera del try: perder la carrera no es una falla de la run.
        ReconciliationRunEntity run = runRepository.saveAndFlush(
                ReconciliationRunEntity.start(processor, settlementDate, fileHash, clock.instant()));

        try {
            return process(run, lines);
        } catch (RuntimeException e) {
            failedRunRecorder.recordFailure(processor, settlementDate, fileHash);
            throw e; // relanzar: si nos la tragáramos, Spring haría COMMIT de lo parcial
        }
    }

    /** Lectura de una run ya completada. La usa la facade después de perder la carrera. */
    @Transactional(readOnly = true)
    public Optional<RunOutcome> findCompleted(String processor, LocalDate settlementDate, String fileHash) {
        return lookupCompleted(processor, settlementDate, fileHash);
    }

    // Privado y sin anotación a propósito: lo llaman los dos métodos públicos dentro de SU
    // transacción. Si reconcile() llamara a this.findCompleted(), el readOnly se ignoraría
    // (self-invocation) — acá no rompe nada, pero confunde al que lee.
    private Optional<RunOutcome> lookupCompleted(String processor, LocalDate settlementDate, String fileHash) {
        return runRepository
                .findByProcessorAndSettlementDateAndFileHashAndStatus(
                        processor, settlementDate, fileHash, RunStatus.COMPLETED)
                .map(run -> new RunOutcome(run.getId(),
                        summaryOf(resultRepository.countByType(run.getId(), run.getSettlementDate())),
                        true));
    }

    private RunOutcome process(ReconciliationRunEntity run, List<SettlementLine> lines) {
        List<LedgerEntryEntity> ledgerEntities =
                ledgerRepository.findByProcessorAndSettlementDate(run.getProcessor(), run.getSettlementDate());
        List<LedgerEntry> ledger = ledgerEntities.stream().map(LedgerEntryEntity::toDomain).toList();

        List<ReconciliationResult> results = engine.reconcile(ledger, lines);

        resultRepository.saveAll(results.stream()
                .map(result -> ReconciliationResultEntity.of(result, run))
                .toList());

        // Marcar las entradas matcheadas. Dirty checking → UPDATE ... WHERE id = ? AND version = ?
        // en el flush de abajo. Si otra tx las tocó desde que las leímos: ObjectOptimisticLocking-
        // FailureException → la run falla entera (rollback + FAILED) y la facade reintenta.
        Map<String, LedgerEntryEntity> byReference = ledgerEntities.stream()
                .collect(Collectors.toMap(LedgerEntryEntity::getProcessorReference, Function.identity()));
        for (ReconciliationResult result : results) {
            if (result instanceof Matched matched) { // pattern matching para instanceof (Java 16)
                byReference.get(matched.processorReference()).markReconciled(run.getId());
            }
        }

        // Sin save(): la run está "managed" en el contexto de persistencia; Hibernate detecta
        // el cambio (dirty checking) y emite el UPDATE en el flush.
        run.complete(clock.instant());

        // Flush explícito: sin esto los INSERT/UPDATE (en batch) salen recién en el commit, que
        // ocurre en el proxy DESPUÉS de que este método retornó → un error de la base (FK,
        // constraint, valor demasiado largo) se escaparía del try/catch y no se registraría el FAILED.
        resultRepository.flush();

        return new RunOutcome(run.getId(), ReconciliationSummary.of(results), false);
    }

    private static ReconciliationSummary summaryOf(List<ResultTypeCount> counts) {
        int matched = 0, missingInProcessor = 0, missingInLedger = 0, amountMismatch = 0, duplicate = 0;
        for (ResultTypeCount c : counts) {
            int n = c.count().intValue();
            // Switch sobre enum sin default: si se agrega un ResultType, no compila.
            switch (c.type()) {
                case MATCHED -> matched = n;
                case MISSING_IN_PROCESSOR -> missingInProcessor = n;
                case MISSING_IN_LEDGER -> missingInLedger = n;
                case AMOUNT_MISMATCH -> amountMismatch = n;
                case DUPLICATE -> duplicate = n;
            }
        }
        return new ReconciliationSummary(matched, missingInProcessor, missingInLedger, amountMismatch, duplicate);
    }
}
