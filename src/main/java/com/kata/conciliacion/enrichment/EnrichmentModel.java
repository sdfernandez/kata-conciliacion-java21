package com.kata.conciliacion.enrichment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

/** Tipos del enriquecimiento, agrupados para no llenar el paquete de archivos chicos. */
public final class EnrichmentModel {

    private EnrichmentModel() {
    }

    /** Lo que hay que enriquecer: una discrepancia ya detectada. */
    public record DiscrepancyToEnrich(String processorReference, Currency currency) {
    }

    public record MerchantConfig(String merchantId, String name, String tier) {
        /** Valor por defecto si el servicio de merchants no responde. */
        public static final MerchantConfig UNKNOWN = new MerchantConfig(null, "UNKNOWN", "UNKNOWN");
    }

    public enum ProcessorPaymentStatus { SETTLED, REFUNDED, DISPUTED, UNKNOWN }

    /**
     * Resultado. Los datos que no se pudieron obtener quedan en su valor por defecto y se listan
     * en degraded(): el ticket se abre igual, pero quien lo lea sabe qué falta.
     */
    public record EnrichedDiscrepancy(
            String processorReference,
            MerchantConfig merchant,
            Optional<BigDecimal> usdRate,
            ProcessorPaymentStatus processorStatus) {

        public List<String> degraded() {
            List<String> missing = new ArrayList<>();
            if (merchant == MerchantConfig.UNKNOWN) missing.add("merchant");
            if (usdRate.isEmpty()) missing.add("fx");
            if (processorStatus == ProcessorPaymentStatus.UNKNOWN) missing.add("status");
            return missing;
        }
    }

    // ---- Los tres servicios externos (en la kata, stubs con latencia simulada) --------------

    public interface MerchantConfigService {
        MerchantConfig fetch(String processorReference);
    }

    public interface FxRateService {
        BigDecimal usdRate(Currency currency);
    }

    /** Necesita el merchantId que devuelve MerchantConfigService: es la dependencia del grafo. */
    public interface ProcessorStatusService {
        ProcessorPaymentStatus status(String merchantId, String processorReference);
    }
}
