package com.kata.conciliacion.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Un pago registrado en el ledger interno. */
public record LedgerEntry(
        String processorReference,
        Money amount,
        String processor,
        LocalDate settlementDate) {

    public LedgerEntry {
        Objects.requireNonNull(processorReference, "processorReference");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(settlementDate, "settlementDate");
        if (processorReference.isBlank()) {
            throw new IllegalArgumentException("processorReference must not be blank");
        }
    }
}
