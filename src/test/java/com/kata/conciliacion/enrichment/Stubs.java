package com.kata.conciliacion.enrichment;

import com.kata.conciliacion.enrichment.EnrichmentModel.FxRateService;
import com.kata.conciliacion.enrichment.EnrichmentModel.MerchantConfig;
import com.kata.conciliacion.enrichment.EnrichmentModel.MerchantConfigService;
import com.kata.conciliacion.enrichment.EnrichmentModel.ProcessorPaymentStatus;
import com.kata.conciliacion.enrichment.EnrichmentModel.ProcessorStatusService;
import java.math.BigDecimal;

/** Servicios falsos con latencia simulada (sleep = IO bloqueante). */
final class Stubs {

    static final long LATENCY_MS = 100;

    private Stubs() {
    }

    static MerchantConfigService merchants(long latencyMs) {
        return ref -> {
            sleep(latencyMs);
            return new MerchantConfig("M-" + ref, "Tienda " + ref, "GOLD");
        };
    }

    static FxRateService fx(long latencyMs) {
        return currency -> {
            sleep(latencyMs);
            return new BigDecimal("0.18");
        };
    }

    static ProcessorStatusService statuses(long latencyMs) {
        return (merchantId, ref) -> {
            if (merchantId == null) {
                throw new IllegalArgumentException("merchantId is required");
            }
            sleep(latencyMs);
            return ProcessorPaymentStatus.REFUNDED;
        };
    }

    static MerchantConfigService failingMerchants() {
        return ref -> {
            throw new IllegalStateException("merchant service down");
        };
    }

    static FxRateService failingFx() {
        return currency -> {
            throw new IllegalStateException("fx service down");
        };
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
