package com.kata.conciliacion.parser;

import com.kata.conciliacion.domain.SettlementLine;

/**
 * Resultado de parsear una línea: válida o inválida. No usamos Optional porque Optional
 * modela ausencia, no error: perderíamos el motivo del fallo.
 */
public sealed interface ParsedLine {

    record Valid(SettlementLine line) implements ParsedLine {
    }

    record Invalid(LineError error) implements ParsedLine {
    }
}
