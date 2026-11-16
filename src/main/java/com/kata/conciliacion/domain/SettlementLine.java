package com.kata.conciliacion.domain;

import java.time.Instant;
import java.util.Objects;

/** Una línea del archivo de liquidación que envía el procesador. */
public record SettlementLine(String processorReference, Money amount, Instant settledAt) {

    public SettlementLine {
        Objects.requireNonNull(processorReference, "processorReference");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(settledAt, "settledAt");
        if (processorReference.isBlank()) {
            throw new IllegalArgumentException("processorReference must not be blank");
        }
    }
}
