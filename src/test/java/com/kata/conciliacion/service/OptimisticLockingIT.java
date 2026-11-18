package com.kata.conciliacion.service;

import com.kata.conciliacion.TestcontainersConfiguration;
import com.kata.conciliacion.domain.LedgerEntry;
import com.kata.conciliacion.domain.Money;
import com.kata.conciliacion.persistence.LedgerEntryEntity;
import com.kata.conciliacion.persistence.LedgerEntryRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OptimisticLockingIT {

    static final LocalDate DAY = LocalDate.of(2026, 11, 12);

    @Autowired LedgerEntryRepository ledgerRepository;
    @Autowired ReconciliationFacade facade;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;

    TransactionTemplate tx;
    TransactionTemplate newTx; // REQUIRES_NEW: otra tx (otra conexión, otro EntityManager) en el mismo hilo

    Long entryId;
    Long runA;
    Long runB;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE reconciliation_result, reconciliation_run, ledger_entry, outbox_event, processed_event, discrepancy_ticket");
        tx = new TransactionTemplate(txManager);
        newTx = new TransactionTemplate(txManager);
        newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        entryId = ledgerRepository.save(LedgerEntryEntity.fromDomain(new LedgerEntry(
                "PR-1", new Money(new BigDecimal("100.00"), Currency.getInstance("BRL")), "PROC_BR", DAY)))
                .getId();
        runA = insertRun();
        runB = insertRun();
    }

    @Test
    void staleUpdateIsRejectedInsteadOfSilentlyOverwriting() {
        assertThatThrownBy(() -> tx.execute(status -> {
            // Tx 1 lee la entrada: version = 0
            LedgerEntryEntity stale = ledgerRepository.findById(entryId).orElseThrow();
            assertThat(stale.getVersion()).isZero();

            // Mientras tanto, tx 2 lee la misma entrada, la modifica y commitea: version = 1
            newTx.executeWithoutResult(s ->
                    ledgerRepository.findById(entryId).orElseThrow().markReconciled(runB));

            // Tx 1 modifica su copia vieja. Flush → UPDATE ... SET version = 1 WHERE id = ? AND version = 0
            // → 0 filas afectadas → Hibernate sabe que alguien se le adelantó.
            stale.markReconciled(runA);
            ledgerRepository.flush();
            return null;
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // Sin lost update: quedó lo de tx 2, no lo pisó tx 1.
        LedgerEntryEntity current = ledgerRepository.findById(entryId).orElseThrow();
        assertThat(current.getReconciledRunId()).isEqualTo(runB);
        assertThat(current.getVersion()).isEqualTo(1);
    }

    @Test
    void retryInNewTransactionRereadsAndSucceeds() {
        // El conflicto anterior, y después el manejo correcto: transacción NUEVA, releer, reaplicar.
        newTx.executeWithoutResult(s -> ledgerRepository.findById(entryId).orElseThrow().markReconciled(runB));

        tx.executeWithoutResult(s -> {
            LedgerEntryEntity fresh = ledgerRepository.findById(entryId).orElseThrow(); // version = 1
            fresh.markReconciled(runA);
        });

        LedgerEntryEntity current = ledgerRepository.findById(entryId).orElseThrow();
        assertThat(current.getReconciledRunId()).isEqualTo(runA);
        assertThat(current.getVersion()).isEqualTo(2);
    }

    @Test
    void reconciliationMarksMatchedEntriesAndBumpsVersion() {
        byte[] file = """
                processor_reference,amount,currency,settled_at
                PR-1,100.00,BRL,2026-11-12T10:00:00Z
                """.getBytes(StandardCharsets.UTF_8);

        RunOutcome outcome = facade.reconcile("PROC_BR", DAY, file);

        LedgerEntryEntity entry = ledgerRepository.findById(entryId).orElseThrow();
        assertThat(entry.getReconciledRunId()).isEqualTo(outcome.runId());
        assertThat(entry.getVersion()).isEqualTo(1);
    }

    private Long insertRun() {
        return jdbc.queryForObject("""
                INSERT INTO reconciliation_run (id, processor, settlement_date, status, started_at)
                VALUES (nextval('reconciliation_run_seq'), 'PROC_BR', ?, 'COMPLETED', now())
                RETURNING id
                """, Long.class, DAY);
    }
}
