package com.kata.conciliacion.enrichment;

import com.kata.conciliacion.enrichment.EnrichmentModel.DiscrepancyToEnrich;
import com.kata.conciliacion.enrichment.EnrichmentModel.EnrichedDiscrepancy;
import com.kata.conciliacion.enrichment.EnrichmentModel.FxRateService;
import com.kata.conciliacion.enrichment.EnrichmentModel.MerchantConfig;
import com.kata.conciliacion.enrichment.EnrichmentModel.MerchantConfigService;
import com.kata.conciliacion.enrichment.EnrichmentModel.ProcessorPaymentStatus;
import com.kata.conciliacion.enrichment.EnrichmentModel.ProcessorStatusService;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Línea de base: una llamada después de la otra. Latencia = SUMA de las tres.
 *
 * <p>Fijate que acá no hay forma de poner un timeout: una llamada bloqueante espera lo que tarde.
 * El timeout tendría que venir del cliente HTTP de cada servicio (T8).
 */
public final class SequentialEnricher {

    private final MerchantConfigService merchants;
    private final FxRateService fx;
    private final ProcessorStatusService statuses;

    public SequentialEnricher(MerchantConfigService merchants, FxRateService fx, ProcessorStatusService statuses) {
        this.merchants = merchants;
        this.fx = fx;
        this.statuses = statuses;
    }

    public EnrichedDiscrepancy enrich(DiscrepancyToEnrich d) {
        MerchantConfig merchant;
        try {
            merchant = merchants.fetch(d.processorReference());
        } catch (RuntimeException e) {
            merchant = MerchantConfig.UNKNOWN;
        }

        Optional<BigDecimal> rate;
        try {
            rate = Optional.of(fx.usdRate(d.currency()));
        } catch (RuntimeException e) {
            rate = Optional.empty();
        }

        ProcessorPaymentStatus status = ProcessorPaymentStatus.UNKNOWN;
        if (merchant != MerchantConfig.UNKNOWN) {
            try {
                status = statuses.status(merchant.merchantId(), d.processorReference());
            } catch (RuntimeException e) {
                // degradar
            }
        }
        return new EnrichedDiscrepancy(d.processorReference(), merchant, rate, status);
    }
}
