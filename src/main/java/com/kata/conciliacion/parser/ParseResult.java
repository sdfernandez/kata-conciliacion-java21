package com.kata.conciliacion.parser;

import com.kata.conciliacion.domain.SettlementLine;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Líneas válidas (incluidos los duplicados) y errores por línea. */
public record ParseResult(List<SettlementLine> lines, List<LineError> errors) {

    public ParseResult {
        lines = List.copyOf(lines);
        errors = List.copyOf(errors);
    }

    /**
     * Referencias que aparecen más de una vez, con todas sus líneas.
     * Las referencias únicas no aparecen en el mapa.
     */
    public Map<String, List<SettlementLine>> duplicates() { 
        Map<String, List<SettlementLine>> byReference = lines.stream().collect(Collectors.groupingBy(SettlementLine::processorReference));

        return byReference.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
