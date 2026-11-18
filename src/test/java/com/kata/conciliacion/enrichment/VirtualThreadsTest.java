package com.kata.conciliacion.enrichment;

import com.kata.conciliacion.enrichment.EnrichmentModel.DiscrepancyToEnrich;
import com.kata.conciliacion.enrichment.EnrichmentModel.EnrichedDiscrepancy;
import com.kata.conciliacion.enrichment.EnrichmentModel.FxRateService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.IntStream;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

import static com.kata.conciliacion.enrichment.Stubs.LATENCY_MS;
import static org.assertj.core.api.Assertions.assertThat;

class VirtualThreadsTest {

    static final DiscrepancyToEnrich PR2 = new DiscrepancyToEnrich("PR-2", Currency.getInstance("BRL"));
    static final int CORES = Runtime.getRuntime().availableProcessors(); // = carrier threads por defecto

    // ---- Parte A reescrita -----------------------------------------------------------------

    @Test
    void sequentialLookingCodeRunsInParallel() {
        var enricher = new VirtualThreadEnricher(Stubs.merchants(LATENCY_MS), Stubs.fx(LATENCY_MS),
                Stubs.statuses(LATENCY_MS), Duration.ofMillis(500));

        long start = System.nanoTime();
        EnrichedDiscrepancy result = enricher.enrich(PR2);
        long elapsed = millisSince(start);

        assertThat(result.degraded()).isEmpty();
        System.out.printf("Virtual-thread enricher: %d ms%n", elapsed);
        assertThat(elapsed).isBetween(2 * LATENCY_MS, 3 * LATENCY_MS - 30); // mismo camino crítico
    }

    @Test
    void timeoutCancelsAndInterruptsTheSlowCall() {
        AtomicBoolean interrupted = new AtomicBoolean();
        FxRateService hangingFx = currency -> {
            try {
                Thread.sleep(5_000);
                return BigDecimal.ONE;
            } catch (InterruptedException e) {
                interrupted.set(true); // cancel(true) llegó hasta acá
                throw new IllegalStateException(e);
            }
        };
        var enricher = new VirtualThreadEnricher(Stubs.merchants(LATENCY_MS), hangingFx,
                Stubs.statuses(LATENCY_MS), Duration.ofMillis(300));

        long start = System.nanoTime();
        EnrichedDiscrepancy result = enricher.enrich(PR2);
        long elapsed = millisSince(start);

        assertThat(result.degraded()).containsExactly("fx");
        // Volvió por timeout Y el close() del executor no esperó los 5 s: la tarea fue interrumpida.
        // Contraste con orTimeout (Parte A), que dejaba el hilo bloqueado.
        assertThat(elapsed).isLessThan(1_000);
        assertThat(interrupted).isTrue();
    }

    @Test
    void virtualThreadsScaleBlockingIoWithoutSizingAPool() throws Exception {
        int tasks = 200;
        var sequential = new SequentialEnricher(Stubs.merchants(LATENCY_MS), Stubs.fx(LATENCY_MS),
                Stubs.statuses(LATENCY_MS));

        // Hilos de plataforma: 50 hilos → 200 tareas de 300 ms en 4 rondas ≈ 1.2 s.
        long platformMs;
        try (ExecutorService platform = Executors.newFixedThreadPool(50)) {
            platformMs = timeAll(platform, tasks, () -> sequential.enrich(PR2));
        }

        // Virtual threads: uno por tarea, las 200 a la vez ≈ 300 ms. Sin dimensionar nada.
        long virtualMs;
        try (ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor()) {
            virtualMs = timeAll(virtual, tasks, () -> sequential.enrich(PR2));
        }

        System.out.printf("%d enrichments — platform pool(50): %d ms, virtual threads: %d ms%n",
                tasks, platformMs, virtualMs);
        assertThat(virtualMs).isLessThan(platformMs / 2);
    }

    // ---- Pinning ---------------------------------------------------------------------------

    @Test
    void synchronizedWithIoPinsTheCarrierAndJfrSeesIt() throws Exception {
        List<RecordedEvent> pinned = recordPinnedEvents(() ->
                runConcurrently(new KeyedCache.Synchronized<>(slowLoader(50)), 20));

        System.out.printf("synchronized: %d jdk.VirtualThreadPinned events%n", pinned.size());
        assertThat(pinned).isNotEmpty();
        // El evento trae el stack trace: apunta derecho a la línea del synchronized.
        assertThat(pinned.getFirst().getStackTrace().getFrames())
                .anyMatch(f -> f.getMethod().getType().getName().contains("KeyedCache$Synchronized"));
    }

    @Test
    void reentrantLockDoesNotPin() throws Exception {
        List<RecordedEvent> pinned = recordPinnedEvents(() ->
                runConcurrently(new KeyedCache.Locking<>(slowLoader(50)), 20));

        assertThat(pinned).isEmpty();
    }

    @Test
    void pinningCapsConcurrencyAtTheNumberOfCarriers() {
        // 4 × cores claves DISTINTAS: sin contención de locks. La única diferencia es el pinning.
        int keys = 4 * CORES;

        long syncMs = runConcurrently(new KeyedCache.Synchronized<>(slowLoader(100)), keys);
        long lockMs = runConcurrently(new KeyedCache.Locking<>(slowLoader(100)), keys);

        // synchronized: solo CORES cargas a la vez (una por carrier) → 4 rondas de 100 ms.
        // ReentrantLock: las 4×CORES a la vez → ~1 ronda.
        System.out.printf("%d loads on %d carriers — synchronized: %d ms, ReentrantLock: %d ms%n",
                keys, CORES, syncMs, lockMs);
        assertThat(syncMs).isGreaterThanOrEqualTo(350);
        assertThat(lockMs).isLessThan(250);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static Function<Integer, String> slowLoader(long millis) {
        return key -> {
            Stubs.sleep(millis); // "IO" remota
            return "value-" + key;
        };
    }

    /** Pide {@code keys} claves distintas, cada una desde su propio virtual thread. */
    private static long runConcurrently(KeyedCache<Integer, String> cache, int keys) {
        long start = System.nanoTime();
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, keys).forEach(k -> vt.submit(() -> cache.get(k)));
        } // close() espera a todas
        return millisSince(start);
    }

    /** Graba con JFR mientras corre {@code action} y devuelve los eventos de pinning. */
    private static List<RecordedEvent> recordPinnedEvents(Runnable action) throws Exception {
        Path file = Files.createTempFile("pinning", ".jfr");
        try (Recording recording = new Recording()) {
            // Por defecto el evento tiene umbral de 20 ms; lo bajamos para no perder ninguno.
            // Y el stack trace no se graba si no se pide: sin él, sabés QUE hubo pinning pero no DÓNDE.
            recording.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(1)).withStackTrace();
            recording.start();
            action.run();
            recording.stop();
            recording.dump(file);
        }
        try {
            return RecordingFile.readAllEvents(file).stream()
                    .filter(e -> e.getEventType().getName().equals("jdk.VirtualThreadPinned"))
                    .toList();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static long timeAll(ExecutorService executor, int tasks, Runnable task) throws Exception {
        long start = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            futures.add(executor.submit(task));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        return millisSince(start);
    }

    private static long millisSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
