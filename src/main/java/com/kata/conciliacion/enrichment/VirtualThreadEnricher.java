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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * El mismo grafo que AsyncEnricher, escrito como código bloqueante "de toda la vida":
 * se lee de arriba abajo, try/catch normal, stack traces completos, debugger que funciona.
 *
 * <p>Escala igual que el asíncrono porque un virtual thread que se bloquea en IO NO ocupa un hilo
 * del sistema operativo: la JVM lo "desmonta" de su carrier thread y el carrier atiende a otro.
 * Crear uno cuesta ~1 KB, no ~1 MB: se crea uno por tarea y se tira. NO se usan en pools.
 *
 * <p>(Java 21 tiene StructuredTaskScope para esto mismo, más prolijo — cancela a los hermanos si
 * uno falla — pero es preview: necesita --enable-preview. Por eso acá va ExecutorService.)
 */
public final class VirtualThreadEnricher {

    private final MerchantConfigService merchants;
    private final FxRateService fx;
    private final ProcessorStatusService statuses;
    private final long timeoutMillis;

    public VirtualThreadEnricher(MerchantConfigService merchants, FxRateService fx,
                                 ProcessorStatusService statuses, Duration timeoutPerCall) {
        this.merchants = merchants;
        this.fx = fx;
        this.statuses = statuses;
        this.timeoutMillis = timeoutPerCall.toMillis();
    }

    public EnrichedDiscrepancy enrich(DiscrepancyToEnrich d) {
        String ref = d.processorReference();

        // try-with-resources: close() ESPERA a que terminen todas las tareas. Por eso, al hacer
        // timeout, hay que cancelar la tarea (ver await): si no, close() se quedaría esperando.
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<MerchantConfig> merchantF = vt.submit(() -> merchants.fetch(ref));
            Future<BigDecimal> fxF = vt.submit(() -> fx.usdRate(d.currency()));   // en paralelo

            MerchantConfig merchant = await(merchantF).orElse(MerchantConfig.UNKNOWN);

            // La dependencia es simplemente... la línea siguiente.
            ProcessorPaymentStatus status = ProcessorPaymentStatus.UNKNOWN;
            if (merchant != MerchantConfig.UNKNOWN) {
                status = await(vt.submit(() -> statuses.status(merchant.merchantId(), ref)))
                        .orElse(ProcessorPaymentStatus.UNKNOWN);
            }

            Optional<BigDecimal> rate = await(fxF);
            return new EnrichedDiscrepancy(ref, merchant, rate, status);
        }
    }

    /**
     * Espera con timeout y, si vence, CANCELA con interrupción. A diferencia de orTimeout en
     * CompletableFuture, cancel(true) sobre un Future de un executor sí interrumpe el hilo: la
     * llamada bloqueante (sleep, IO interrumpible) termina y no queda un hilo zombie.
     */
    private <T> Optional<T> await(Future<T> future) {
        try {
            return Optional.ofNullable(future.get(timeoutMillis, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            return Optional.empty();
        } catch (ExecutionException e) {
            return Optional.empty(); // degradar (la causa real está en e.getCause())
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // nunca tragarse la interrupción
            return Optional.empty();
        }
    }
}
