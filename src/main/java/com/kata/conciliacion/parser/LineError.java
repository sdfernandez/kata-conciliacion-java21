package com.kata.conciliacion.parser;

/** Una línea del archivo que no se pudo parsear, con su número (1 = header) y el motivo. */
public record LineError(int lineNumber, String rawLine, String reason) {
}
