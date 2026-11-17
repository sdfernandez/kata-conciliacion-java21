package com.kata.conciliacion.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class ReconciliationEngineTest {

    private final ReconciliationEngine engine = new ReconciliationEngine();

    // --- Ejemplos ya escritos (modelo de estilo) ---

    @Test
    void emptyInputsGiveEmptyResult() {
        assertThat(engine.reconcile(List.of(), List.of())).isEmpty();
    }

    @Test
    void sameReferenceAmountAndCurrencyIsMatched() {
        var entry = ledger("PR-1", "100.00", "BRL");
        var line = settlement("PR-1", "100.00", "BRL");

        assertThat(engine.reconcile(List.of(entry), List.of(line)))
                .containsExactly(new Matched(entry, line));
    }

    // --- Para escribir (Sergio) ---

    @Test
    void sameAmountWithDifferentScaleIsMatched() {
        var entry = ledger("PR-1", "100.0", "BRL");
        var line = settlement("PR-1", "100.00", "BRL");

        assertThat(engine.reconcile(List.of(entry), List.of(line)))
                .containsExactly(new Matched(entry, line));
    }

    @Test
    void differentAmountIsAmountMismatch() {
        var entry = ledger("PR-1", "100.02", "BRL");
        var line = settlement("PR-1", "100.00", "BRL");

        assertThat(engine.reconcile(List.of(entry), List.of(line)))
                .containsExactly(new AmountMismatch(entry, line));
    }

    @Test
    void sameAmountDifferentCurrencyIsAmountMismatch() {
        var entry = ledger("PR-1", "100.00", "USD");
        var line = settlement("PR-1", "100.00", "BRL");

        assertThat(engine.reconcile(List.of(entry), List.of(line)))
                .containsExactly(new AmountMismatch(entry, line));
    }

    @Test
    void onlyInLedgerIsMissingInProcessor() {
        var entry = ledger("PR-1", "100.00", "BRL");

        assertThat(engine.reconcile(List.of(entry), List.of()))
                .containsExactly(new MissingInProcessor(entry));
    }

    @Test
    void onlyInFileIsMissingInLedger() {
        var line = settlement("PR-1", "100.00", "BRL");

        assertThat(engine.reconcile(List.of(), List.of(line)))
                .containsExactly(new MissingInLedger(line));
    }

    @Test
    void duplicateWinsOverAmountMismatch() {
        var entry = ledger("PR-1", "100.00", "BRL");
        var first = settlement("PR-1", "100.00", "BRL");
        var second = settlement("PR-1", "999.00", "BRL");

        // Un solo resultado: ni AmountMismatch por la segunda línea, ni MissingInProcessor
        // por la entrada del ledger (el Duplicate la consume). Las líneas, en orden de archivo.
        assertThat(engine.reconcile(List.of(entry), List.of(first, second)))
                .containsExactly(new Duplicate("PR-1", List.of(first, second)));
    }

    @Test
    void duplicateNotInLedgerIsDuplicate() {
        var first = settlement("PR-1", "100.00", "BRL");
        var second = settlement("PR-1", "100.00", "BRL");

        // Duplicate y no MissingInLedger: la duplicación tiene prioridad sobre todo.
        assertThat(engine.reconcile(List.of(), List.of(first, second)))
                .containsExactly(new Duplicate("PR-1", List.of(first, second)));
    }

    @Test
    void duplicateReferenceInLedgerIsRejected() {
        var ledger = List.of(
                ledger("PR-1", "100.00", "BRL"),
                ledger("PR-1", "200.00", "BRL"));

        // Precondición rota (bug nuestro, no una discrepancia): argumento inválido, con la referencia
        // en el mensaje para poder diagnosticarlo en producción.
        assertThatThrownBy(() -> engine.reconcile(ledger, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PR-1");
    }

    @Test
    void resultsAreSortedByReference() {
        // Entrada desordenada a propósito, un tipo distinto por referencia.
        var ledger = List.of(
                ledger("PR-3", "100.00", "BRL"),
                ledger("PR-2", "100.00", "BRL"));
        var settlement = List.of(
                settlement("PR-3", "100.00", "BRL"),
                settlement("PR-1", "100.00", "BRL"));

        assertThat(engine.reconcile(ledger, settlement))
                .extracting(ReconciliationResult::processorReference)
                .containsExactly("PR-1", "PR-2", "PR-3");
    }

    // --- Helpers ---

    private static LedgerEntry ledger(String ref, String amount, String currency) {
        return new LedgerEntry(ref, money(amount, currency), "PROC_BR", LocalDate.parse("2026-11-12"));
    }

    private static SettlementLine settlement(String ref, String amount, String currency) {
        return new SettlementLine(ref, money(amount, currency), Instant.parse("2026-11-12T10:15:00Z"));
    }

    private static Money money(String amount, String currency) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currency));
    }
}
