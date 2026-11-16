package com.kata.conciliacion.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Monto monetario inmutable.
 *
 * <p>Invariantes: amount y currency no nulos, escala <= 2, amount normalizado a escala 2
 * (para que el equals/hashCode generado por el record sea correcto).
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.stripTrailingZeros();
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("Amount scale must be <= 2, got " + amount.toPlainString());
        }
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
