package com.kata.conciliacion.service;

import com.kata.conciliacion.TestcontainersConfiguration;
import com.kata.conciliacion.domain.LedgerEntry;
import com.kata.conciliacion.domain.Money;
import com.kata.conciliacion.engine.ReconciliationSummary;
import com.kata.conciliacion.persistence.LedgerEntryEntity;
import com.kata.conciliacion.persistence.LedgerEntryRepository;
import com.kata.conciliacion.persistence.ReconciliationRunEntity;
import com.kata.conciliacion.persistence.ReconciliationRunRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotencyIT {

    static final String BR = "PROC_BR";
    static final LocalDate DAY = LocalDate.of(2026, 11, 12);
    static final Currency BRL = Currency.getInstance("BRL");

    static final byte[] FILE = """
            processor_reference,amount,currency,settled_at
            PR-1,100.00,BRL,2026-11-12T10:00:00Z
            PR-2,55.00,BRL,2026-11-12T10:05:00Z
            PR-4,20.00,BRL,2026-11-12T10:10:00Z
            """.getBytes(StandardCharsets.UTF_8);

    @Autowired ReconciliationFacade facade;
    @Autowired FailedRunRecorder failedRunRecorder;
    @Autowired LedgerEntryRepository ledgerRepository;
    @Autowired ReconciliationRunRepository runRepository;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE reconciliation_result, reconciliation_run, ledger_entry");
        ledgerRepository.saveAll(List.of(ledger("PR-1", "100.00"), ledger("PR-2", "50.00"), ledger("PR-3", "10.00")));
    }

    @Test
    void reprocessingSameFileReturnsExistingRunWithoutDuplicates() {
        RunOutcome first = facade.reconcile(BR, DAY, FILE);
        RunOutcome second = facade.reconcile(BR, DAY, FILE);

        assertThat(first.alreadyProcessed()).isFalse();
        assertThat(second.alreadyProcessed()).isTrue();
        assertThat(second.runId()).isEqualTo(first.runId());
        // El resumen de la segunda sale de la base (GROUP BY) y coincide con el calculado en memoria.
        assertThat(second.summary()).isEqualTo(first.summary())
                .isEqualTo(new ReconciliationSummary(1, 1, 1, 1, 0));
        assertThat(count("reconciliation_run")).isEqualTo(1);
        assertThat(count("reconciliation_result")).isEqualTo(4);
    }

    @Test
    void differentFileForSameDayIsANewRun() {
        byte[] corrected = (new String(FILE, StandardCharsets.UTF_8) + "PR-3,10.00,BRL,2026-11-12T10:15:00Z\n")
                .getBytes(StandardCharsets.UTF_8);

        RunOutcome first = facade.reconcile(BR, DAY, FILE);
        RunOutcome second = facade.reconcile(BR, DAY, corrected);

        assertThat(second.alreadyProcessed()).isFalse();
        assertThat(second.runId()).isNotEqualTo(first.runId());
    }

    @Test
    void failedRunDoesNotBlockRetry() {
        // Un intento anterior falló con este mismo archivo: hay una run FAILED con la misma clave.
        failedRunRecorder.recordFailure(BR, DAY, ReconciliationFacade.sha256(FILE));

        RunOutcome retry = facade.reconcile(BR, DAY, FILE);

        // El índice único es parcial (WHERE status <> 'FAILED'): el reintento corre normalmente.
        assertThat(retry.alreadyProcessed()).isFalse();
        assertThat(jdbc.queryForList("SELECT status FROM reconciliation_run ORDER BY id", String.class))
                .containsExactly("FAILED", "COMPLETED");
    }

    @Test
    void concurrentInsertWaitsForWinnerAndThenReturnsItsRun() throws Exception {
        CountDownLatch winnerInserted = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);

        // ExecutorService es AutoCloseable desde Java 19: close() espera a que terminen las tareas.
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // Hilo A: inserta la run con la clave y deja la transacción ABIERTA (sin commit).
            Future<Long> winner = pool.submit(() -> tx.execute(status -> {
                Instant now = Instant.now();
                var run = ReconciliationRunEntity.start(BR, DAY, ReconciliationFacade.sha256(FILE), now);
                run.complete(now);
                runRepository.saveAndFlush(run);
                winnerInserted.countDown();
                await(releaseWinner);
                return run.getId();
            }));
            await(winnerInserted);

            // Hilo B: el fast path no ve la run de A (no commiteada) → intenta insertar → BLOQUEADO.
            Future<RunOutcome> loser = pool.submit(() -> facade.reconcile(BR, DAY, FILE));
            awaitOneSessionBlockedOnLock();
            assertThat(loser.isDone()).isFalse();

            // A commitea → el INSERT de B falla por la clave única → la facade devuelve la run de A.
            releaseWinner.countDown();
            RunOutcome outcome = loser.get(10, TimeUnit.SECONDS);

            assertThat(outcome.runId()).isEqualTo(winner.get());
            assertThat(outcome.alreadyProcessed()).isTrue();
        }
        // B no grabó resultados ni dejó una run FAILED: perder la carrera no es una falla.
        assertThat(count("reconciliation_run")).isEqualTo(1);
        assertThat(count("reconciliation_result")).isZero();
    }

    @Test
    void manyThreadsWithSameFileProcessItExactlyOnce() throws Exception {
        int threads = 8;
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<RunOutcome>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    await(startGate); // todos arrancan a la vez
                    return facade.reconcile(BR, DAY, FILE);
                }));
            }
            startGate.countDown();

            List<RunOutcome> outcomes = new ArrayList<>();
            for (Future<RunOutcome> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }

            assertThat(outcomes).extracting(RunOutcome::runId).containsOnly(outcomes.getFirst().runId());
            assertThat(outcomes).filteredOn(o -> !o.alreadyProcessed()).hasSize(1);
        }
        assertThat(count("reconciliation_run")).isEqualTo(1);
        assertThat(count("reconciliation_result")).isEqualTo(4);
    }

    private void awaitOneSessionBlockedOnLock() throws InterruptedException {
        // Esperar a que Postgres muestre a B esperando un lock (el de la tx de A sobre la clave).
        for (int i = 0; i < 100; i++) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiting != null && waiting == 1) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Loser never blocked on the unique index");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    static LedgerEntryEntity ledger(String ref, String amount) {
        return LedgerEntryEntity.fromDomain(new LedgerEntry(ref, new Money(new BigDecimal(amount), BRL), BR, DAY));
    }
}
