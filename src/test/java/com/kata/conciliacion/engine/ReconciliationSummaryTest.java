package com.kata.conciliacion.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.kata.conciliacion.domain.LedgerEntry;
import com.kata.conciliacion.domain.Money;
import com.kata.conciliacion.domain.ReconciliationResult;
import com.kata.conciliacion.domain.ReconciliationResult.AmountMismatch;
import com.kata.conciliacion.domain.ReconciliationResult.Duplicate;
import com.kata.conciliacion.domain.ReconciliationResult.Matched;
import com.kata.conciliacion.domain.ReconciliationResult.MissingInLedger;
import com.kata.conciliacion.domain.ReconciliationResult.MissingInProcessor;
import com.kata.conciliacion.domain.SettlementLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconciliationSummaryTest {

    @Test
    void emptyListGivesAllZeros() {
        var summary = ReconciliationSummary.of(List.of());

        assertThat(summary).isEqualTo(new ReconciliationSummary(0, 0, 0, 0, 0));
        assertThat(summary.total()).isZero();
    }

    @Test
    void countsEachTypeInItsOwnField() {
        // Cantidades distintas por tipo, para detectar si dos contadores quedaron cruzados.
        List<ReconciliationResult> results = List.of(
                new Matched(ledger("PR-1", "10.00"), settlement("PR-1", "10.00")),
                new Matched(ledger("PR-2", "10.00"), settlement("PR-2", "10.00")),
                new Matched(ledger("PR-3", "10.00"), settlement("PR-3", "10.00")),
                new MissingInProcessor(ledger("PR-4", "10.00")),
                new MissingInLedger(settlement("PR-5", "10.00")),
                new MissingInLedger(settlement("PR-6", "10.00")),
                new AmountMismatch(ledger("PR-7", "10.00"), settlement("PR-7", "99.00")),
                new AmountMismatch(ledger("PR-8", "10.00"), settlement("PR-8", "99.00")),
                new AmountMismatch(ledger("PR-9", "10.00"), settlement("PR-9", "99.00")),
                new AmountMismatch(ledger("PR-10", "10.00"), settlement("PR-10", "99.00")),
                new Duplicate("PR-11", List.of(settlement("PR-11", "10.00"), settlement("PR-11", "10.00"))));

        var summary = ReconciliationSummary.of(results);

        assertThat(summary).isEqualTo(new ReconciliationSummary(3, 1, 2, 4, 1));
        assertThat(summary.total()).isEqualTo(results.size());
    }

    private static LedgerEntry ledger(String ref, String amount) {
        return new LedgerEntry(ref, money(amount), "PROC_BR", LocalDate.parse("2026-11-12"));
    }

    private static SettlementLine settlement(String ref, String amount) {
        return new SettlementLine(ref, money(amount), Instant.parse("2026-11-12T10:15:00Z"));
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount), Currency.getInstance("BRL"));
    }
}
