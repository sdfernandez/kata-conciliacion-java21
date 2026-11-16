package com.kata.conciliacion.parser;

import com.kata.conciliacion.domain.Money;
import com.kata.conciliacion.domain.SettlementLine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Parser del archivo de liquidación (CSV con header).
 *
 * <pre>
 * processor_reference,amount,currency,settled_at
 * PR-0001,1500.00,BRL,2026-11-12T10:15:00Z
 * </pre>
 */
public class SettlementFileParser {

    private static final int EXPECTED_FIELDS = 4;

    public ParseResult parse(Path file) {
        // Files.lines es lazy y mantiene el archivo abierto hasta que se cierra el stream:
        // sin try-with-resources queda un file handle abierto.
        try (Stream<String> lines = Files.lines(file)) {
            return parse(lines);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /**
     * Núcleo del parser. La primera línea es el header y se saltea. Las líneas en blanco se
     * ignoran. Las inválidas no cortan el proceso: van a {@link ParseResult#errors()}.
     */
    public ParseResult parse(Stream<String> lines) {
        record NumberedLine(int number, String text) { }   // solo existe dentro de este método
        var counter = new AtomicInteger();

        List<ParsedLine> parsed = lines
                .map(text -> new NumberedLine(counter.incrementAndGet(), text))
                .skip(1)
                .filter(line -> !line.text().isBlank())
                .map(line -> parseLine(line.number(), line.text()))
                .toList();

        var valid = new ArrayList<SettlementLine>();
        var errors = new ArrayList<LineError>();
        for (ParsedLine p : parsed) {
            switch (p) {
                case ParsedLine.Valid v   -> valid.add(v.line());
                case ParsedLine.Invalid i -> errors.add(i.error());
            }
        }
        return new ParseResult(valid, errors);
    }

    ParsedLine parseLine(int lineNumber, String raw) {
        // -1: sin él, split descarta los campos vacíos del final ("a,b,," -> 2 campos, no 4).
        String[] fields = raw.split(",", -1);
        if (fields.length != EXPECTED_FIELDS) {
            return invalid(lineNumber, raw,
                    "expected " + EXPECTED_FIELDS + " fields, got " + fields.length);
        }
        try {
            String reference = fields[0].strip();
            BigDecimal amount = new BigDecimal(fields[1].strip());
            Currency currency = Currency.getInstance(fields[2].strip());
            Instant settledAt = Instant.parse(fields[3].strip());
            return new ParsedLine.Valid(
                    new SettlementLine(reference, new Money(amount, currency), settledAt));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            // IllegalArgumentException cubre: NumberFormatException (subclase), moneda
            // inexistente, escala > 2 en Money y referencia en blanco.
            // No se puede poner NumberFormatException en el multi-catch junto con su
            // superclase: no compila.
            return invalid(lineNumber, raw, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static ParsedLine invalid(int lineNumber, String raw, String reason) {
        return new ParsedLine.Invalid(new LineError(lineNumber, raw, reason));
    }
}
