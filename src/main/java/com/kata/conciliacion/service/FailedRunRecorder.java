package com.kata.conciliacion.service;

import com.kata.conciliacion.persistence.ReconciliationRunEntity;
import com.kata.conciliacion.persistence.ReconciliationRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deja constancia de una run fallida en su PROPIA transacción, que se confirma aunque la
 * transacción del llamador haga rollback.
 *
 * <p>Es un bean aparte a propósito: si fuera un método de ReconciliationService llamado con
 * this., sería self-invocation y el REQUIRES_NEW se ignoraría en silencio (se grabaría dentro
 * de la tx principal y el rollback se lo llevaría puesto).
 *
 * <p>La run FAILED lleva el file_hash (para trazabilidad) pero NO ocupa la clave de idempotencia:
 * el índice único es parcial (WHERE status <> 'FAILED'), así que un reintento puede correr.
 */
@Component
public class FailedRunRecorder {

    private final ReconciliationRunRepository runRepository;
    private final Clock clock;

    public FailedRunRecorder(ReconciliationRunRepository runRepository, Clock clock) {
        this.runRepository = runRepository;
        this.clock = clock;
    }

    /**
     * REQUIRES_NEW: suspende la tx del llamador y abre otra, con OTRA conexión del pool y otro
     * EntityManager. Commit propio al salir; después se reanuda la tx original.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long recordFailure(String processor, LocalDate settlementDate, String fileHash) {
        Instant now = clock.instant();
        ReconciliationRunEntity run = ReconciliationRunEntity.start(processor, settlementDate, fileHash, now);
        run.fail(now);
        return runRepository.save(run).getId();
    }
}
