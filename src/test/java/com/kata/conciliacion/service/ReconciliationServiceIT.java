package com.kata.conciliacion.service;

import com.kata.conciliacion.TestcontainersConfiguration;
import com.kata.conciliacion.domain.LedgerEntry;
import com.kata.conciliacion.domain.Money;
import com.kata.conciliacion.domain.SettlementLine;
import com.kata.conciliacion.engine.ReconciliationSummary;
import com.kata.conciliacion.persistence.LedgerEntryEntity;
import com.kata.conciliacion.persistence.LedgerEntryRepository;
import com.kata.conciliacion.persistence.ReconciliationResultEntity;
import com.kata.conciliacion.persistence.ReconciliationResultRepository;
import com.kata.conciliacion.persistence.ReconciliationRunRepository;
import com.kata.conciliacion.persistence.ResultType;
import com.kata.conciliacion.persistence.RunStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Sin @Transactional en el test, a propósito: si el test abriera la transacción, el servicio se
 * "uniría" a ella (REQUIRED), nunca habría commit real y no probaríamos lo que pasa en producción.
 * El costo: hay que limpiar la base a mano entre tests.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReconciliationServiceIT {

    static final String BR = "PROC_BR";
    static final LocalDate DAY = LocalDate.of(2026, 11, 12);
    static final Currency BRL = Currency.getInstance("BRL");

    @Autowired ReconciliationService service;
    @Autowired LedgerEntryRepository ledgerRepository;
    @Autowired ReconciliationRunRepository runRepository;
    @Autowired ReconciliationResultRepository resultRepository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE reconciliation_result, reconciliation_run, ledger_entry");
    }

    @Test
    void persistsRunAndOneResultPerReference() {
        ledgerRepository.saveAll(List.of(
                ledger(BR, "PR-1", "100.00", DAY),
                ledger(BR, "PR-2", "50.00", DAY),
                ledger(BR, "PR-3", "10.00", DAY),
                ledger("PROC_MX", "PR-9", "1.00", DAY),     // otro procesador: se ignora
                ledger(BR, "PR-8", "1.00", DAY.minusDays(1)) // otra fecha: se ignora
        ));

        List<SettlementLine> file = List.of(
                line("PR-1", "100.0"),  // misma plata, otra escala → Matched
                line("PR-2", "55.00"),  // AmountMismatch
                line("PR-4", "20.00"),  // MissingInLedger
                line("PR-5", "7.00"),
                line("PR-5", "7.00"));  // Duplicate
        // PR-3 no viene en el archivo → MissingInProcessor

        RunOutcome outcome = service.reconcile(BR, DAY, file);

        assertThat(outcome.summary()).isEqualTo(new ReconciliationSummary(1, 1, 1, 1, 1));

        var run = runRepository.findById(outcome.runId()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getFinishedAt()).isNotNull();

        assertThat(resultRepository.findByRunId(outcome.runId()))
                .extracting(ReconciliationResultEntity::getProcessorReference,
                        ReconciliationResultEntity::getResultType)
                .containsExactlyInAnyOrder(
                        tuple("PR-1", ResultType.MATCHED),
                        tuple("PR-2", ResultType.AMOUNT_MISMATCH),
                        tuple("PR-3", ResultType.MISSING_IN_PROCESSOR),
                        tuple("PR-4", ResultType.MISSING_IN_LEDGER),
                        tuple("PR-5", ResultType.DUPLICATE));
    }

    @Test
    void storesBothAmountsForMismatch() {
        ledgerRepository.save(ledger(BR, "PR-2", "50.00", DAY));

        RunOutcome outcome = service.reconcile(BR, DAY, List.of(line("PR-2", "55.00")));

        var result = resultRepository.findByRunId(outcome.runId()).getFirst();
        // compareTo y no equals: la base podría devolver otra escala (acá NUMERIC(19,2) da 2).
        assertThat(result.getLedgerAmount()).isEqualByComparingTo("50.00");
        assertThat(result.getFileAmount()).isEqualByComparingTo("55.00");
    }

    @Test
    void failureRollsBackEverythingButRecordsFailedRun() {
        ledgerRepository.save(ledger(BR, "PR-1", "100.00", DAY));
        String tooLong = "X".repeat(65); // processor_reference es VARCHAR(64) → falla el INSERT

        assertThatThrownBy(() -> service.reconcile(BR, DAY, List.of(
                line("PR-1", "100.00"),
                line(tooLong, "1.00"))))
                .isInstanceOf(DataIntegrityViolationException.class);

        // La tx principal hizo rollback: ni la run RUNNING ni el resultado Matched de PR-1.
        assertThat(resultRepository.count()).isZero();
        // Lo único que quedó es la run FAILED, confirmada en su propia tx (REQUIRES_NEW).
        assertThat(runRepository.findAll())
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
                    assertThat(run.getProcessor()).isEqualTo(BR);
                });
    }

    static LedgerEntryEntity ledger(String processor, String ref, String amount, LocalDate date) {
        return LedgerEntryEntity.fromDomain(
                new LedgerEntry(ref, new Money(new BigDecimal(amount), BRL), processor, date));
    }

    static SettlementLine line(String ref, String amount) {
        return new SettlementLine(ref, new Money(new BigDecimal(amount), BRL),
                Instant.parse("2026-11-12T10:00:00Z"));
    }
}
