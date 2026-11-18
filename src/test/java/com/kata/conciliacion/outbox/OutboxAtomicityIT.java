package com.kata.conciliacion.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kata.conciliacion.TestcontainersConfiguration;
import com.kata.conciliacion.domain.LedgerEntry;
import com.kata.conciliacion.domain.Money;
import com.kata.conciliacion.persistence.LedgerEntryEntity;
import com.kata.conciliacion.persistence.LedgerEntryRepository;
import com.kata.conciliacion.service.ReconciliationFacade;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxAtomicityIT {

    static final LocalDate DAY = LocalDate.of(2026, 11, 12);
    static final Currency BRL = Currency.getInstance("BRL");

    @Autowired ReconciliationFacade facade;
    @Autowired LedgerEntryRepository ledgerRepository;
    @Autowired OutboxEventRepository outboxRepository;
    @Autowired EntityManagerFactory emf;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE reconciliation_result, reconciliation_run, ledger_entry, outbox_event, processed_event, discrepancy_ticket");
        ledgerRepository.saveAll(List.of(ledger("PR-1", "100.00"), ledger("PR-2", "50.00"), ledger("PR-3", "10.00")));
    }

    @Test
    void oneEventPerDiscrepancyCommittedWithTheResults() throws Exception {
        facade.reconcile("PROC_BR", DAY, file("""
                PR-1,100.00,BRL,2026-11-12T10:00:00Z
                PR-2,55.00,BRL,2026-11-12T10:05:00Z
                PR-4,20.00,BRL,2026-11-12T10:10:00Z
                """));

        // PR-1 Matched (sin evento), PR-2 AmountMismatch, PR-3 MissingInProcessor, PR-4 MissingInLedger.
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event ORDER BY event_type", String.class))
                .containsExactly("AmountMismatch", "MissingInLedger", "MissingInProcessor");

        JsonNode mismatch = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE event_type = 'AmountMismatch'", String.class));
        assertThat(mismatch.get("processorReference").asText()).isEqualTo("PR-2");
        // Montos como string en el JSON: nadie los parsea como double.
        assertThat(mismatch.at("/ledgerAmount/amount").isTextual()).isTrue();
        assertThat(mismatch.at("/ledgerAmount/amount").asText()).isEqualTo("50.00");
        assertThat(mismatch.at("/fileAmount/amount").asText()).isEqualTo("55.00");
        assertThat(mismatch.get("settlementDate").asText()).isEqualTo("2026-11-12");
    }

    @Test
    void failedReconciliationLeavesNoOrphanEvents() {
        String tooLong = "X".repeat(65); // falla el INSERT del resultado → rollback de todo

        assertThatThrownBy(() -> facade.reconcile("PROC_BR", DAY, file("""
                PR-2,55.00,BRL,2026-11-12T10:05:00Z
                %s,1.00,BRL,2026-11-12T10:10:00Z
                """.formatted(tooLong))));

        // El evento de PR-2 se había "guardado" en la misma tx: el rollback se lo llevó. Si lo
        // hubiéramos mandado directo al broker, el consumidor ya estaría investigando un resultado
        // que no existe.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_result", Integer.class)).isZero();
    }

    @Test
    void assignedUuidIsInsertedWithoutPreviousSelect() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        tx.executeWithoutResult(s -> {
            outboxRepository.save(OutboxEventEntity.pending("Test", "1", "Ping", "{}", Instant.now()));
            outboxRepository.flush();
        });

        // Gracias a Persistable.isNew(): persist → 1 INSERT. Sin eso: merge → SELECT + INSERT = 2.
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
    }

    static LedgerEntryEntity ledger(String ref, String amount) {
        return LedgerEntryEntity.fromDomain(
                new LedgerEntry(ref, new Money(new BigDecimal(amount), BRL), "PROC_BR", DAY));
    }

    static byte[] file(String body) {
        return ("processor_reference,amount,currency,settled_at\n" + body).getBytes(StandardCharsets.UTF_8);
    }
}
