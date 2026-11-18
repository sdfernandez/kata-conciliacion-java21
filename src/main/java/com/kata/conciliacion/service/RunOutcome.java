package com.kata.conciliacion.service;

import com.kata.conciliacion.engine.ReconciliationSummary;

/**
 * Lo que devuelve el servicio: un record, no la entidad (que no debe salir de la transacción).
 *
 * @param alreadyProcessed true si el archivo ya se había conciliado y se devolvió la run existente
 */
public record RunOutcome(Long runId, ReconciliationSummary summary, boolean alreadyProcessed) {
}
