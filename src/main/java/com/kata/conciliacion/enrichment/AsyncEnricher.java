package com.kata.conciliacion.enrichment;

import com.kata.conciliacion.enrichment.EnrichmentModel.DiscrepancyToEnrich;
import com.kata.conciliacion.enrichment.EnrichmentModel.EnrichedDiscrepancy;
import com.kata.conciliacion.enrichment.EnrichmentModel.FxRateService;
import com.kata.conciliacion.enrichment.EnrichmentModel.MerchantConfig;
import com.kata.conciliacion.enrichment.EnrichmentModel.MerchantConfigService;
import com.kata.conciliacion.enrichment.EnrichmentModel.ProcessorPaymentStatus;
import com.kata.conciliacion.enrichment.EnrichmentModel.ProcessorStatusService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enriquecimiento con CompletableFuture. Latencia = CAMINO CRÍTICO del grafo, no la suma:
 * <pre>
 *   merchant ──thenCompose──► status ─┐
 *   fx ───────────────────────────────┴── allOf ──► EnrichedDiscrepancy
 * </pre>
 * merchant + status = 200 ms en serie (dependencia real); fx corre en paralelo → total ≈ 200 ms.
 */
public final class AsyncEnricher {

    private static final Logger log = LoggerFactory.getLogger(AsyncEnricher.class);

    private final MerchantConfigService merchants;
    private final FxRateService fx;
    private final ProcessorStatusService statuses;
    private final Executor ioExecutor;
    private final long timeoutMillis;

    /**
     * @param ioExecutor executor PROPIO para IO. Nunca el ForkJoinPool.commonPool() (el default de
     *     supplyAsync sin executor): tiene cores-1 hilos, está pensado para CPU, y lo comparte toda
     *     la JVM (parallel streams incluidos). Llenarlo de llamadas bloqueantes lo deja sin hilos
     *     para todos los demás.
     */
    public AsyncEnricher(MerchantConfigService merchants, FxRateService fx, ProcessorStatusService statuses,
                         Executor ioExecutor, Duration timeoutPerCall) {
        this.merchants = merchants;
        this.fx = fx;
        this.statuses = statuses;
        this.ioExecutor = ioExecutor;
        this.timeoutMillis = timeoutPerCall.toMillis();
    }

    public CompletableFuture<EnrichedDiscrepancy> enrich(DiscrepancyToEnrich d) {
        String ref = d.processorReference();

        // 1) merchant: arranca ya.
        CompletableFuture<MerchantConfig> merchant = call(() -> merchants.fetch(ref));

        // 2) status DEPENDE de merchant → thenCompose (el flatMap de los futures).
        //    thenApply acá devolvería CompletableFuture<CompletableFuture<Status>>.
        //    Si merchant falla, thenCompose ni se ejecuta: la falla se propaga y la atrapa exceptionally.
        CompletableFuture<ProcessorPaymentStatus> status = merchant
                .thenCompose(m -> call(() -> statuses.status(m.merchantId(), ref)))
                .exceptionally(ex -> degrade("status", ref, ex, ProcessorPaymentStatus.UNKNOWN));

        // 3) fx: independiente, arranca YA, en paralelo con merchant.
        CompletableFuture<Optional<BigDecimal>> rate = call(() -> fx.usdRate(d.currency()))
                .thenApply(Optional::of)
                .exceptionally(ex -> degrade("fx", ref, ex, Optional.empty()));

        // merchant degradado para el resultado. Ojo: va DESPUÉS de armar status sobre el merchant
        // original; si status colgara de esta versión, recibiría UNKNOWN (merchantId null) y
        // llamaría al servicio igual.
        CompletableFuture<MerchantConfig> merchantOrUnknown =
                merchant.exceptionally(ex -> degrade("merchant", ref, ex, MerchantConfig.UNKNOWN));

        // 4) Juntar. allOf para N futures (thenCombine sirve para exactamente 2). Los join() de
        //    adentro no bloquean: allOf garantiza que ya terminaron todos.
        return CompletableFuture.allOf(merchantOrUnknown, status, rate)
                .thenApply(ignored -> new EnrichedDiscrepancy(
                        ref, merchantOrUnknown.join(), rate.join(), status.join()));
    }

    /**
     * Una llamada remota: en el executor de IO y con timeout.
     *
     * <p>TRAMPA: orTimeout completa el FUTURE con TimeoutException, pero NO interrumpe ni cancela la
     * tarea. El hilo del pool sigue bloqueado en la llamada lenta hasta que vuelva. Con un servicio
     * colgado, el pool se llena de hilos "zombies" aunque los clientes ya recibieron respuesta.
     * Por eso el timeout real tiene que estar también en el cliente HTTP (T8).
     */
    private <T> CompletableFuture<T> call(java.util.function.Supplier<T> remoteCall) {
        try {
            return CompletableFuture.supplyAsync(remoteCall, ioExecutor)
                    .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // TRAMPA: si el executor rechaza (bulkhead saturado con AbortPolicy), supplyAsync NO
            // devuelve un future fallido: TIRA la excepción de forma sincrónica, y enrich() explotaría
            // en vez de degradar. La convertimos en future fallido para que la tome exceptionally.
            return CompletableFuture.failedFuture(e);
        }
    }

    private static <T> T degrade(String what, String ref, Throwable ex, T fallback) {
        // TRAMPA: en las etapas encadenadas la excepción llega envuelta en CompletionException.
        // Para loguear / decidir según la causa real hay que desenvolverla.
        Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
        log.warn("Enrichment of {} degraded: {} unavailable ({})", ref, what, cause.toString());
        return fallback;
    }
}
