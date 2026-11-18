package com.kata.conciliacion.service;

import com.kata.conciliacion.persistence.DiscrepancyView;
import com.kata.conciliacion.persistence.ReconciliationResultRepository;
import com.kata.conciliacion.persistence.ResultType;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reporte de discrepancias del día (todo lo que no es MATCHED), con el procesador de cada una. */
@Service
public class DiscrepancyReportService {

    private final ReconciliationResultRepository resultRepository;

    public DiscrepancyReportService(ReconciliationResultRepository resultRepository) {
        this.resultRepository = resultRepository;
    }

    /**
     * readOnly = true: Hibernate pone el flush en MANUAL y no guarda snapshots para dirty checking
     * (menos memoria y CPU), y el pool/driver puede rutear a una réplica de lectura.
     */
    @Transactional(readOnly = true)
    public List<DiscrepancyView> discrepancies(LocalDate settlementDate) {
        return resultRepository.findDiscrepancyViews(settlementDate, ResultType.MATCHED);
    }
}
