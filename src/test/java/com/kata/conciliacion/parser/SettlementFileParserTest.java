package com.kata.conciliacion.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.kata.conciliacion.domain.Money;
import com.kata.conciliacion.domain.SettlementLine;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettlementFileParserTest {

    private final SettlementFileParser parser = new SettlementFileParser();

    @Test
    void parsesValidLines() {
        var csv = """
                processor_reference,amount,currency,settled_at
                PR-0001,1500.00,BRL,2026-11-12T10:15:00Z
                PR-0002,99.90,MXN,2026-11-12T11:02:00Z
                """;

        ParseResult result = parser.parse(csv.lines());

        assertThat(result.errors()).isEmpty();
        assertThat(result.lines()).containsExactly(
                new SettlementLine("PR-0001",
                        new Money(new BigDecimal("1500.00"), Currency.getInstance("BRL")),
                        Instant.parse("2026-11-12T10:15:00Z")),
                new SettlementLine("PR-0002",
                        new Money(new BigDecimal("99.90"), Currency.getInstance("MXN")),
                        Instant.parse("2026-11-12T11:02:00Z")));
    }

    @Test
    void collectsInvalidLinesWithoutFailingTheFile() {
        var csv = """
                processor_reference,amount,currency,settled_at
                PR-0001,1500.00,BRL,2026-11-12T10:15:00Z
                PR-0002,abc,BRL,2026-11-12T10:15:00Z
                PR-0003,10.00,XXX1,2026-11-12T10:15:00Z
                PR-0004,10.00,BRL,not-a-date
                PR-0005,10.00,BRL
                PR-0006,10.005,BRL,2026-11-12T10:15:00Z
                """;

        ParseResult result = parser.parse(csv.lines());

        assertThat(result.lines())
                .extracting(SettlementLine::processorReference)
                .containsExactly("PR-0001");
        assertThat(result.errors())
                .extracting(LineError::lineNumber)
                .containsExactly(3, 4, 5, 6, 7);
    }

    @Test
    void blankLinesAreSkippedButKeepFileLineNumbers() {
        var csv = """
                processor_reference,amount,currency,settled_at
                PR-0001,1500.00,BRL,2026-11-12T10:15:00Z

                PR-0002,abc,BRL,2026-11-12T10:15:00Z
                """;

        ParseResult result = parser.parse(csv.lines());

        assertThat(result.lines()).hasSize(1);
        assertThat(result.errors())
                .singleElement()
                .extracting(LineError::lineNumber)
                .isEqualTo(4); // la línea en blanco cuenta: es la 3
    }

    @Test
    void headerOnlyFileIsEmpty() {
        var csv = """
                processor_reference,amount,currency,settled_at
                """;

        ParseResult result = parser.parse(csv.lines());

        assertThat(result.lines()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void detectsDuplicateReferences() {
        var csv = """
                processor_reference,amount,currency,settled_at
                PR-0001,1500.00,BRL,2026-11-12T10:15:00Z
                PR-0002,99.90,MXN,2026-11-12T11:02:00Z
                PR-0001,1500.00,BRL,2026-11-12T10:16:00Z
                """;

        ParseResult result = parser.parse(csv.lines());

        assertThat(result.duplicates()).containsOnlyKeys("PR-0001");
        assertThat(result.duplicates().get("PR-0001")).hasSize(2);
    }

    @Test
    void parsesFromFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settlement.csv");
        Files.writeString(file, """
                processor_reference,amount,currency,settled_at
                PR-0001,1500.00,BRL,2026-11-12T10:15:00Z
                """);

        ParseResult result = parser.parse(file);

        assertThat(result.lines()).hasSize(1);
        assertThat(result.errors()).isEmpty();
    }
}
