package com.kata.conciliacion.engine;

import com.kata.conciliacion.domain.ReconciliationResult;
import com.kata.conciliacion.domain.ReconciliationResult.AmountMismatch;
import com.kata.conciliacion.domain.ReconciliationResult.Duplicate;
import com.kata.conciliacion.domain.ReconciliationResult.Matched;
import com.kata.conciliacion.domain.ReconciliationResult.MissingInLedger;
import com.kata.conciliacion.domain.ReconciliationResult.MissingInProcessor;
import java.util.List;

/** Cantidad de resultados por tipo. */
public record ReconciliationSummary(
        int matched,
        int missingInProcessor,
        int missingInLedger,
        int amountMismatch,
        int duplicate) {

    public static ReconciliationSummary of(List<ReconciliationResult> results) {
        // El record es inmutable: se cuenta en variables locales y se construye una sola vez al final.
        int matched = 0;
        int missingInProcessor = 0;
        int missingInLedger = 0;
        int amountMismatch = 0;
        int duplicate = 0;

        for (ReconciliationResult result : results) {
            // Sin default: si se agrega un tipo a la sealed interface, esto deja de compilar.
            switch (result) {
                case Matched m -> matched++;
                case MissingInProcessor m -> missingInProcessor++;
                case MissingInLedger m -> missingInLedger++;
                case AmountMismatch m -> amountMismatch++;
                case Duplicate d -> duplicate++;
            }
        }

        return new ReconciliationSummary(matched, missingInProcessor, missingInLedger, amountMismatch, duplicate);
    }

    public int total() {
        return matched + missingInProcessor + missingInLedger + amountMismatch + duplicate;
    }
}
