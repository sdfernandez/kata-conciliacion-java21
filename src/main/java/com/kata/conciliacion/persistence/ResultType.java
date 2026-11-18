package com.kata.conciliacion.persistence;

/** Discriminador de la tabla reconciliation_result. Espejo de la sealed interface del dominio. */
public enum ResultType {
    MATCHED,
    MISSING_IN_PROCESSOR,
    MISSING_IN_LEDGER,
    AMOUNT_MISMATCH,
    DUPLICATE
}
