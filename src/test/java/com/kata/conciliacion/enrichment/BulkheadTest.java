package com.kata.conciliacion.enrichment;

import com.kata.conciliacion.enrichment.EnrichmentModel.DiscrepancyToEnrich;
import com.kata.conciliacion.enrichment.EnrichmentModel.EnrichedDiscrepancy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BulkheadTest {

    final List<ExecutorService> pools = new ArrayList<>();

    @AfterEach
    void shutdown() {
        pools.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void saturatedNonCriticalPoolDoesNotSlowCriticalWork() throws Exception {
        ThreadPoolExecutor nonCritical = track(Bulkheads.nonCritical(4, 4));
        ThreadPoolExecutor critical = track(Bulkheads.critical(4, 10));

        // Un servicio de enriquecimiento se cuelga: 20 tareas de 2 s contra un pool de 4 + cola de 4.
        int rejected = 0;
        for (int i = 0; i < 20; i++) {
            try {
                nonCritical.execute(() -> Stubs.sleep(2_000));
            } catch (RejectedExecutionException e) {
                rejected++;
            }
        }
        assertThat(rejected).isEqualTo(20 - 4 - 4); // 4 corriendo, 4 en cola, el resto rechazado

        // Mientras tanto, la conciliación sigue con sus propios hilos.
        long start = System.nanoTime();
        critical.submit(() -> Stubs.sleep(10)).get(1, TimeUnit.SECONDS);
        long elapsed = millisSince(start);

        System.out.printf("Critical task with separate bulkheads: %d ms%n", elapsed);
        assertThat(elapsed).isLessThan(150);
    }

    @Test
    void sharedPoolStarvesCriticalWork() throws Exception {
        // Contraejemplo: UN pool para todo (y con cola sin límite, como newFixedThreadPool).
        ExecutorService shared = track(Executors.newFixedThreadPool(4));
        for (int i = 0; i < 20; i++) {
            shared.execute(() -> Stubs.sleep(200)); // enriquecimiento lento
        }

        long start = System.nanoTime();
        shared.submit(() -> Stubs.sleep(10)).get(5, TimeUnit.SECONDS); // la tarea crítica
        long elapsed = millisSince(start);

        // Queda detrás de 20 tareas / 4 hilos = 5 rondas de 200 ms. Nadie rechazó nada: solo espera.
        System.out.printf("Critical task in a shared pool: %d ms%n", elapsed);
        assertThat(elapsed).isGreaterThan(900);
    }

    @Test
    void callerRunsPolicySlowsDownTheProducer() {
        ThreadPoolExecutor critical = track(Bulkheads.critical(1, 1));
        List<String> ranOn = new java.util.concurrent.CopyOnWriteArrayList<>();
        Runnable work = () -> {
            ranOn.add(Thread.currentThread().getName());
            Stubs.sleep(200);
        };

        long start = System.nanoTime();
        critical.execute(work); // lo toma el único hilo
        critical.execute(work); // va a la cola
        critical.execute(work); // pool y cola llenos → lo ejecuta ESTE hilo (el productor)
        long submitTime = millisSince(start);

        // No se perdió trabajo y el productor quedó frenado ~200 ms: backpressure natural.
        assertThat(ranOn).contains(Thread.currentThread().getName());
        assertThat(submitTime).isGreaterThanOrEqualTo(190);
    }

    @Test
    void saturatedEnrichmentPoolDegradesInsteadOfThrowing() {
        ThreadPoolExecutor nonCritical = track(Bulkheads.nonCritical(1, 1));
        nonCritical.execute(() -> Stubs.sleep(2_000)); // ocupa el hilo
        nonCritical.execute(() -> Stubs.sleep(2_000)); // ocupa la cola

        var enricher = new AsyncEnricher(Stubs.merchants(10), Stubs.fx(10), Stubs.statuses(10),
                nonCritical, Duration.ofMillis(500));

        // Sin el catch de RejectedExecutionException en AsyncEnricher.call(), esta línea TIRARÍA.
        CompletableFuture<EnrichedDiscrepancy> future =
                enricher.enrich(new DiscrepancyToEnrich("PR-9", Currency.getInstance("MXN")));

        assertThat(future.join().degraded()).containsExactly("merchant", "fx", "status");
    }

    @Test
    void sizingFormulas() {
        assertThat(Bulkheads.Sizing.cpuBound(12)).isEqualTo(12);
        // 100 ms de espera de red por cada 5 ms de CPU → cada core puede "atender" 21 hilos.
        assertThat(Bulkheads.Sizing.ioBound(12, 100, 5)).isEqualTo(252);
    }

    private <T extends ExecutorService> T track(T pool) {
        pools.add(pool);
        return pool;
    }

    private static long millisSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
