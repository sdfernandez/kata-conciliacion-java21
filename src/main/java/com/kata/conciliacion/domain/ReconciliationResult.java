package com.kata.conciliacion.domain;

import java.util.List;
import java.util.Objects;

/**
 * Resultado de conciliar un pago. Jerarquía cerrada: el switch sobre este tipo es exhaustivo
 * sin default, y agregar un caso nuevo rompe la compilación de todo switch que no lo maneje.
 *
 * <p>Los records están anidados en el mismo archivo, así que el permits lo infiere el compilador.
 */
public sealed interface ReconciliationResult {

    /** Clave de matching; común a todos los casos. */
    String processorReference();

    /** Está en las dos fuentes con el mismo monto y moneda. */
    record Matched(LedgerEntry ledgerEntry, SettlementLine settlementLine)
            implements ReconciliationResult {

        public Matched {
            requireSameReference(ledgerEntry, settlementLine);
        }

        @Override
        public String processorReference() {
            return ledgerEntry.processorReference();
        }
    }

    /** Está en el ledger pero el procesador no lo liquidó. */
    record MissingInProcessor(LedgerEntry ledgerEntry) implements ReconciliationResult {

        public MissingInProcessor {
            Objects.requireNonNull(ledgerEntry, "ledgerEntry");
        }

        @Override
        public String processorReference() {
            return ledgerEntry.processorReference();
        }
    }

    /** El procesador lo liquidó pero no está en el ledger. */
    record MissingInLedger(SettlementLine settlementLine) implements ReconciliationResult {

        public MissingInLedger {
            Objects.requireNonNull(settlementLine, "settlementLine");
        }

        @Override
        public String processorReference() {
            return settlementLine.processorReference();
        }
    }

    /** Está en las dos fuentes, pero el monto o la moneda no coinciden. */
    record AmountMismatch(LedgerEntry ledgerEntry, SettlementLine settlementLine)
            implements ReconciliationResult {

        public AmountMismatch {
            requireSameReference(ledgerEntry, settlementLine);
        }

        @Override
        public String processorReference() {
            return ledgerEntry.processorReference();
        }
    }

    /**
     * La misma referencia aparece más de una vez en el archivo. Tiene prioridad sobre el resto:
     * si hay duplicado no se intenta matchear (aunque además haya mismatch de monto).
     */
    record Duplicate(String processorReference, List<SettlementLine> settlementLines)
            implements ReconciliationResult {

        public Duplicate {
            Objects.requireNonNull(processorReference, "processorReference");
            settlementLines = List.copyOf(settlementLines); // copia defensiva + rechaza nulos
            if (settlementLines.size() < 2) {
                throw new IllegalArgumentException("Duplicate needs at least 2 lines");
            }
            if (!settlementLines.stream()
                    .allMatch(l -> l.processorReference().equals(processorReference))) {
                throw new IllegalArgumentException("All lines must share " + processorReference);
            }
        }
        // processorReference() lo genera el record y ya implementa el método de la interfaz.
    }

    private static void requireSameReference(LedgerEntry ledgerEntry, SettlementLine settlementLine) {
        Objects.requireNonNull(ledgerEntry, "ledgerEntry");
        Objects.requireNonNull(settlementLine, "settlementLine");
        if (!ledgerEntry.processorReference().equals(settlementLine.processorReference())) {
            throw new IllegalArgumentException("References differ: "
                    + ledgerEntry.processorReference() + " vs " + settlementLine.processorReference());
        }
    }
}
