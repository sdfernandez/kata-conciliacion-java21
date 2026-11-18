package com.kata.conciliacion.persistence;

import com.kata.conciliacion.TestcontainersConfiguration;
import com.kata.conciliacion.domain.LedgerEntry;
import com.kata.conciliacion.domain.Money;
import com.kata.conciliacion.domain.SettlementLine;
import com.kata.conciliacion.service.ReconciliationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PartitioningIT {

    static final Currency BRL = Currency.getInstance("BRL");

    @Autowired ReconciliationService service;
    @Autowired LedgerEntryRepository ledgerRepository;
    @Autowired ResultPartitionMaintenance maintenance;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE reconciliation_result, reconciliation_run, ledger_entry");
    }

    @Test
    void resultsAreRoutedToTheirMonthPartition() {
        LocalDate day = LocalDate.of(2026, 11, 12);
        ledgerRepository.save(LedgerEntryEntity.fromDomain(new LedgerEntry("PR-1", brl("10.00"), "PROC_BR", day)));

        service.reconcile("PROC_BR", day, "test-hash", List.of(line("PR-1", "10.00"), line("PR-2", "5.00")));

        // tableoid dice en qué partición física vive cada fila.
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT tableoid::regclass::text FROM reconciliation_result", String.class))
                .containsExactly("reconciliation_result_2026_11");
    }

    @Test
    void filteringByPartitionKeyScansOnlyOnePartition() {
        List<String> plan = jdbc.queryForList(
                "EXPLAIN SELECT * FROM reconciliation_result WHERE settlement_date = DATE '2026-11-12'",
                String.class);

        // Partition pruning: el planner descarta las otras 11 particiones.
        assertThat(String.join("\n", plan))
                .contains("reconciliation_result_2026_11")
                .doesNotContain("reconciliation_result_2026_10", "reconciliation_result_2026_12");
    }

    @Test
    void retentionDetachesAndDropsOnlyOldPartitions() {
        maintenance.createPartitions(YearMonth.of(2020, 1), 3); // 2020_01, 2020_02, 2020_03
        insertRawResult(LocalDate.of(2020, 1, 15));
        insertRawResult(LocalDate.of(2020, 3, 15));

        List<String> dropped = maintenance.dropPartitionsOlderThan(YearMonth.of(2020, 3));

        assertThat(dropped).containsExactly("reconciliation_result_2020_01", "reconciliation_result_2020_02");
        assertThat(maintenance.existingPartitions())
                .contains("reconciliation_result_2020_03", "reconciliation_result_2026_11")
                .doesNotContain("reconciliation_result_2020_01", "reconciliation_result_2020_02");
        // La fila de enero desapareció con su partición, sin un solo DELETE. La de marzo sigue.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_result", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void insertForMonthWithoutPartitionFailsLoudly() {
        // Sin partición DEFAULT: un mes no creado no se "traga" en silencio.
        assertThatThrownBy(() -> insertRawResult(LocalDate.of(2031, 1, 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("no partition of relation");
    }

    private void insertRawResult(LocalDate date) {
        Long runId = jdbc.queryForObject("""
                INSERT INTO reconciliation_run (id, processor, settlement_date, status, started_at)
                VALUES (nextval('reconciliation_run_seq'), 'PROC_BR', ?, 'COMPLETED', now())
                RETURNING id
                """, Long.class, date);
        jdbc.update("""
                INSERT INTO reconciliation_result (id, run_id, settlement_date, processor, processor_reference, result_type)
                VALUES (nextval('reconciliation_result_seq'), ?, ?, 'PROC_BR', 'PR-X', 'MISSING_IN_LEDGER')
                """, runId, date);
    }

    static Money brl(String amount) {
        return new Money(new BigDecimal(amount), BRL);
    }

    static SettlementLine line(String ref, String amount) {
        return new SettlementLine(ref, brl(amount), Instant.parse("2026-11-12T10:00:00Z"));
    }
}
