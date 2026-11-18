package com.kata.conciliacion.enrichment;

import com.kata.conciliacion.enrichment.EnrichmentModel.DiscrepancyToEnrich;
import com.kata.conciliacion.enrichment.EnrichmentModel.EnrichedDiscrepancy;
import com.kata.conciliacion.enrichment.EnrichmentModel.MerchantConfig;
import com.kata.conciliacion.enrichment.EnrichmentModel.ProcessorPaymentStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.kata.conciliacion.enrichment.Stubs.LATENCY_MS;
import static org.assertj.core.api.Assertions.assertThat;

class AsyncEnricherTest {

    static final DiscrepancyToEnrich PR2 = new DiscrepancyToEnrich("PR-2", Currency.getInstance("BRL"));
    static final Duration TIMEOUT = Duration.ofMillis(500);

    // Executor propio para IO (no el commonPool). Casteado para poder mirar getActiveCount().
    final ThreadPoolExecutor io = (ThreadPoolExecutor) Executors.newFixedThreadPool(8);

    @AfterEach
    void shutdown() {
        io.shutdownNow();
    }

    @Test
    void sequentialLatencyIsTheSumOfTheCalls() {
        var enricher = new SequentialEnricher(
                Stubs.merchants(LATENCY_MS), Stubs.fx(LATENCY_MS), Stubs.statuses(LATENCY_MS));

        long start = System.nanoTime();
        EnrichedDiscrepancy result = enricher.enrich(PR2);
        long elapsed = millisSince(start);

        assertThat(result.degraded()).isEmpty();
        System.out.printf("Sequential: %d ms%n", elapsed);
        assertThat(elapsed).isGreaterThanOrEqualTo(3 * LATENCY_MS); // 100 + 100 + 100
    }

    @Test
    void asyncLatencyIsTheCriticalPath() {
        var enricher = asyncEnricher(Stubs.merchants(LATENCY_MS), Stubs.fx(LATENCY_MS), TIMEOUT);

        long start = System.nanoTime();
        EnrichedDiscrepancy result = enricher.enrich(PR2).join();
        long elapsed = millisSince(start);

        assertThat(result.merchant().merchantId()).isEqualTo("M-PR-2");
        assertThat(result.usdRate()).contains(new BigDecimal("0.18"));
        assertThat(result.processorStatus()).isEqualTo(ProcessorPaymentStatus.REFUNDED);
        assertThat(result.degraded()).isEmpty();
        System.out.printf("Async: %d ms%n", elapsed);
        // merchant → status (200 ms) en serie; fx en paralelo. Margen por el scheduler de Windows.
        assertThat(elapsed).isBetween(2 * LATENCY_MS, 3 * LATENCY_MS - 30);
    }

    @Test
    void failingFxDegradesInsteadOfFailingTheWholeEnrichment() {
        var enricher = asyncEnricher(Stubs.merchants(LATENCY_MS), Stubs.failingFx(), TIMEOUT);

        EnrichedDiscrepancy result = enricher.enrich(PR2).join();

        assertThat(result.usdRate()).isEmpty();
        assertThat(result.degraded()).containsExactly("fx");
        assertThat(result.processorStatus()).isEqualTo(ProcessorPaymentStatus.REFUNDED); // el resto, OK
    }

    @Test
    void merchantFailureCascadesToStatusButNotToFx() {
        var enricher = asyncEnricher(Stubs.failingMerchants(), Stubs.fx(LATENCY_MS), TIMEOUT);

        EnrichedDiscrepancy result = enricher.enrich(PR2).join();

        // Sin merchantId no se puede preguntar el status: la falla se propaga por thenCompose.
        // fx no depende de merchant y sale bien.
        assertThat(result.merchant()).isEqualTo(MerchantConfig.UNKNOWN);
        assertThat(result.degraded()).containsExactly("merchant", "status");
        assertThat(result.usdRate()).isPresent();
    }

    @Test
    void slowServiceTimesOutAndDegrades() {
        // fx tarda 2 s; el timeout por llamada es 150 ms.
        var enricher = asyncEnricher(Stubs.merchants(LATENCY_MS), Stubs.fx(2_000), Duration.ofMillis(150));

        long start = System.nanoTime();
        EnrichedDiscrepancy result = enricher.enrich(PR2).join();
        long elapsed = millisSince(start);

        assertThat(result.degraded()).containsExactly("fx");
        assertThat(elapsed).isLessThan(500); // no esperamos los 2 s
    }

    @Test
    void timeoutDoesNotCancelTheUnderlyingCall() {
        var enricher = asyncEnricher(Stubs.merchants(LATENCY_MS), Stubs.fx(2_000), Duration.ofMillis(150));

        enricher.enrich(PR2).join(); // ya tenemos respuesta (degradada)...

        // join() vuelve apenas el hilo de status completa el future, antes de que ese hilo se
        // devuelva al pool: durante un instante hay 2 activos. Esperamos a que se estabilice.
        // (getActiveCount es aproximado por contrato; el test flaky original miraba "en caliente".)
        Stubs.sleep(100);

        // ...pero el hilo que llama a fx SIGUE bloqueado en su sleep de 2 s. orTimeout completó el
        // future, no frenó la tarea. Con un servicio colgado y mucho tráfico, el pool se llena de
        // estos hilos y deja de atender: el timeout tiene que estar también en el cliente (T8).
        assertThat(io.getActiveCount()).isEqualTo(1);
    }

    private AsyncEnricher asyncEnricher(EnrichmentModel.MerchantConfigService merchants,
                                        EnrichmentModel.FxRateService fx, Duration timeout) {
        return new AsyncEnricher(merchants, fx, Stubs.statuses(LATENCY_MS), io, timeout);
    }

    private static long millisSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
