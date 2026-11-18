package com.kata.conciliacion.service;

import com.kata.conciliacion.TestcontainersConfiguration;
import com.kata.conciliacion.domain.LedgerEntry;
import com.kata.conciliacion.domain.Money;
import com.kata.conciliacion.domain.SettlementLine;
import com.kata.conciliacion.persistence.DiscrepancyView;
import com.kata.conciliacion.persistence.LedgerEntryEntity;
import com.kata.conciliacion.persistence.LedgerEntryRepository;
import com.kata.conciliacion.persistence.ReconciliationResultRepository;
import com.kata.conciliacion.persistence.ResultType;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cuenta las sentencias SQL con las Statistics de Hibernate. Se activan en runtime (y no con
 * una property en @SpringBootTest) para no cambiar la clave del cache de contextos.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NPlusOneIT {

    static final LocalDate DAY = LocalDate.of(2026, 11, 12);
    static final Currency BRL = Currency.getInstance("BRL");
    static final List<String> PROCESSORS = List.of("PROC_AR", "PROC_BR", "PROC_MX");

    @Autowired ReconciliationService reconciliationService;
    @Autowired DiscrepancyReportService reportService;
    @Autowired LedgerEntryRepository ledgerRepository;
    @Autowired ReconciliationResultRepository resultRepository;
    @Autowired EntityManagerFactory emf;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    Statistics stats;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE reconciliation_result, reconciliation_run, ledger_entry");
        // 3 runs (una por procesador), cada una con 1 Matched + 2 discrepancias → 6 discrepancias.
        for (String processor : PROCESSORS) {
            ledgerRepository.saveAll(List.of(
                    ledger(processor, "OK"), ledger(processor, "DIFF"), ledger(processor, "LOST")));
            reconciliationService.reconcile(processor, DAY, "test-hash", List.of(
                    line(processor, "OK", "10.00"), line(processor, "DIFF", "99.00")));
        }
        stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear(); // contamos solo lo que pasa a partir de acá
    }

    @Test
    void lazyRunAccessedInLoopCausesNPlusOne() {
        List<String> rows = tx.execute(status ->
                resultRepository.findBySettlementDateAndResultTypeNot(DAY, ResultType.MATCHED).stream()
                        // getProcessor() inicializa el proxy → SELECT a reconciliation_run
                        .map(r -> r.getRun().getProcessor() + "/" + r.getProcessorReference())
                        .toList());

        assertThat(rows).hasSize(6);
        // 1 query de resultados + 1 por cada run DISTINTA (3). No es 1 + 6: el contexto de
        // persistencia cachea cada run la primera vez. N = padres distintos, no filas.
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1 + 3);
    }

    @Test
    void joinFetchLoadsEverythingInOneQuery() {
        List<String> rows = tx.execute(status ->
                resultRepository.findWithRunBySettlementDate(DAY, ResultType.MATCHED).stream()
                        .map(r -> r.getRun().getProcessor() + "/" + r.getProcessorReference())
                        .toList());

        assertThat(rows).hasSize(6);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void projectionUsesOneQueryAndLoadsNoEntities() {
        List<DiscrepancyView> report = reportService.discrepancies(DAY);

        assertThat(report).hasSize(6);
        assertThat(report.getFirst().processor()).isEqualTo("PROC_AR"); // order by en la query
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
        assertThat(stats.getEntityLoadCount()).isZero(); // records, no entidades managed
    }

    static LedgerEntryEntity ledger(String processor, String ref) {
        return LedgerEntryEntity.fromDomain(new LedgerEntry(
                processor + "-" + ref, new Money(new BigDecimal("10.00"), BRL), processor, DAY));
    }

    static SettlementLine line(String processor, String ref, String amount) {
        return new SettlementLine(processor + "-" + ref, new Money(new BigDecimal(amount), BRL),
                Instant.parse("2026-11-12T10:00:00Z"));
    }
}
