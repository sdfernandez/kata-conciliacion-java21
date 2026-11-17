package com.kata.conciliacion.engine;

import com.kata.conciliacion.domain.LedgerEntry;
import com.kata.conciliacion.domain.ReconciliationResult;
import com.kata.conciliacion.domain.ReconciliationResult.AmountMismatch;
import com.kata.conciliacion.domain.ReconciliationResult.Duplicate;
import com.kata.conciliacion.domain.ReconciliationResult.Matched;
import com.kata.conciliacion.domain.ReconciliationResult.MissingInLedger;
import com.kata.conciliacion.domain.ReconciliationResult.MissingInProcessor;
import com.kata.conciliacion.domain.SettlementLine;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Motor de conciliación: función pura, sin estado, sin Spring ni base de datos.
 *
 * <p>Precondiciones: el ledger ya viene filtrado por procesador y fecha (lo hace el llamador),
 * y no tiene referencias repetidas (si las tiene, es un bug nuestro: se tira excepción).
 *
 * <p>Reglas:
 * <ul>
 *   <li>Una referencia que aparece 2+ veces en el archivo es {@code Duplicate}, tenga o no
 *       mismatch, y consume la entrada del ledger (no se emite además MissingInProcessor).
 *   <li>El resultado se devuelve ordenado por processorReference (determinístico).
 * </ul>
 *
 * <p>Complejidad O(n + m) por los índices en Map, más O(k log k) del orden final.
 */
public final class ReconciliationEngine {

    private static final Comparator<ReconciliationResult> BY_REFERENCE =
            Comparator.comparing(ReconciliationResult::processorReference);

    public List<ReconciliationResult> reconcile(List<LedgerEntry> ledger, List<SettlementLine> settlement) {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(settlement, "settlement");

        Map<String, LedgerEntry> ledgerByRef = indexLedger(ledger);
        // groupingBy mantiene el orden del archivo dentro de cada lista (lo usa Duplicate).
        Map<String, List<SettlementLine>> fileByRef = settlement.stream()
                .collect(Collectors.groupingBy(SettlementLine::processorReference));

        // Todo lo que aparece en el archivo: Duplicate, Matched, AmountMismatch o MissingInLedger.
        Stream<ReconciliationResult> fromFile = fileByRef.entrySet().stream()
                .map(e -> classify(e.getKey(), e.getValue(), ledgerByRef.get(e.getKey())));

        // Lo que está en el ledger y el archivo no menciona. Si la referencia está en el archivo
        // (aunque sea como duplicado), ya la cubrió fromFile: así el Duplicate "consume" la entrada.
        Stream<ReconciliationResult> onlyInLedger = ledgerByRef.values().stream()
                .filter(entry -> !fileByRef.containsKey(entry.processorReference()))
                .map(MissingInProcessor::new);

        return Stream.concat(fromFile, onlyInLedger)
                .sorted(BY_REFERENCE)
                .toList(); // Java 16: lista inmodificable
    }

    private static Map<String, LedgerEntry> indexLedger(List<LedgerEntry> ledger) {
        return ledger.stream().collect(Collectors.toMap(
                LedgerEntry::processorReference,
                Function.identity(),
                (a, b) -> {
                    // Merge function propia: sin ella toMap tira IllegalStateException con un mensaje
                    // de la JDK. Una referencia repetida en el ledger es un argumento inválido.
                    throw new IllegalArgumentException(
                            "Duplicate processorReference in ledger: " + a.processorReference());
                }));
    }

    private static ReconciliationResult classify(String ref, List<SettlementLine> lines, LedgerEntry entry) {
        if (lines.size() > 1) {
            return new Duplicate(ref, lines); // prioridad sobre todo, haya o no entrada en el ledger
        }
        SettlementLine line = lines.getFirst(); // Java 21: SequencedCollection
        if (entry == null) {
            return new MissingInLedger(line);
        }
        // Money.equals compara monto Y moneda. Es correcto porque Money normaliza la escala en su
        // constructor: dos montos iguales siempre tienen la misma representación BigDecimal.
        return entry.amount().equals(line.amount())
                ? new Matched(entry, line)
                : new AmountMismatch(entry, line);
    }
}
