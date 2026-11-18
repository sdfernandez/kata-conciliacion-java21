package com.kata.conciliacion.persistence;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReconciliationResultRepository extends JpaRepository<ReconciliationResultEntity, Long> {

    // run.id navega la FK sin hacer join (usa la columna run_id directamente).
    List<ReconciliationResultEntity> findByRunId(Long runId);

    /**
     * Versión ingenua: trae los resultados con la run como proxy LAZY. Si después se toca
     * result.getRun().getProcessor(), cada run distinta dispara su propio SELECT → N+1.
     * Queda solo para demostrar el problema en el test.
     */
    List<ReconciliationResultEntity> findBySettlementDateAndResultTypeNot(LocalDate date, ResultType excluded);

    /** Fix 1: JOIN FETCH. Una sola query; las runs vienen inicializadas dentro de cada entidad. */
    @Query("""
            select r from ReconciliationResultEntity r
            join fetch r.run
            where r.settlementDate = :date and r.resultType <> :excluded
            """)
    List<ReconciliationResultEntity> findWithRunBySettlementDate(LocalDate date, ResultType excluded);

    /**
     * Fix 2: proyección. Una sola query, solo las columnas que el reporte usa, sin entidades.
     * Desde V6 usa la columna denormalizada r.processor: ni siquiera hace falta el join con la run.
     */
    @Query("""
            select new com.kata.conciliacion.persistence.DiscrepancyView(
                r.processor, r.processorReference, r.resultType, r.ledgerAmount, r.fileAmount)
            from ReconciliationResultEntity r
            where r.settlementDate = :date and r.resultType <> :excluded
            order by r.processor, r.processorReference
            """)
    List<DiscrepancyView> findDiscrepancyViews(LocalDate date, ResultType excluded);
}
