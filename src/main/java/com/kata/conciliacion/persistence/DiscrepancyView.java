package com.kata.conciliacion.persistence;

import java.math.BigDecimal;

/**
 * Proyección de solo lectura para el reporte. Un record sí sirve acá (no es entidad): Hibernate
 * lo construye con "select new", no lo gestiona el contexto de persistencia, no hay proxies,
 * ni dirty checking, ni LazyInitializationException posible.
 */
public record DiscrepancyView(
        String processor,
        String processorReference,
        ResultType resultType,
        BigDecimal ledgerAmount,
        BigDecimal fileAmount) {
}
