package com.kata.conciliacion.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Currency MXN = Currency.getInstance("MXN");

    @Test
    void sameAmountWithDifferentScaleIsEqual() {
        Money m1 = new Money(new BigDecimal("1500.0"), MXN);
        Money m2 = new Money(new BigDecimal("1500.00"), MXN);
        assertThat(m1).isEqualTo(m2);
        assertThat(m1).hasSameHashCodeAs(m2);
    }

    @Test
    void normalizesAmountToScaleTwo() {
        Money m1 = new Money(new BigDecimal("1500"), MXN);
        assertThat(m1.amount().toPlainString()).isEqualTo("1500.00");
    }

    @Test
    void sameAmountDifferentCurrencyIsNotEqual() {
        Money m1 = new Money(new BigDecimal("1500.00"), MXN);
        Money m2 = new Money(new BigDecimal("1500.00"), BRL);
        assertThat(m1).isNotEqualTo(m2);
    }

    @Test
    void rejectsNullAmount() {
        assertThatThrownBy(() -> new Money(null, MXN))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void rejectsNullCurrency() {
        assertThatThrownBy(() -> new Money(new BigDecimal("100"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void rejectsScaleGreaterThanTwo() {
        assertThatThrownBy(() -> new Money(new BigDecimal("10.005"), MXN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlesTrailingZerosBeyondScaleTwo() {
        Money m1 = new Money(new BigDecimal("10.500"), MXN);
        Money m2 = new Money(new BigDecimal("10.50"), MXN);
        assertThat(m1).isEqualTo(m2);
    }
}
