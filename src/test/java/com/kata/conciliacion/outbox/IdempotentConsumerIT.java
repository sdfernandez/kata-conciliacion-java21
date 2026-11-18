package com.kata.conciliacion.outbox;

import com.kata.conciliacion.TestcontainersConfiguration;
import com.kata.conciliacion.consumer.DiscrepancyTicketConsumer;
import com.kata.conciliacion.domain.LedgerEntry;
import com.kata.conciliacion.domain.Money;
import com.kata.conciliacion.persistence.LedgerEntryEntity;
import com.kata.conciliacion.persistence.LedgerEntryRepository;
import com.kata.conciliacion.service.ReconciliationFacade;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdempotentConsumerIT {

    static final LocalDate DAY = LocalDate.of(2026, 11, 12);

    @Autowired DiscrepancyTicketConsumer consumer;
    @Autowired ReconciliationFacade facade;
    @Autowired LedgerEntryRepository ledgerRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Clock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE reconciliation_result, reconciliation_run, ledger_entry, outbox_event, processed_event, discrepancy_ticket");
    }

    @Test
    void sameEventTwiceCreatesOneTicket() {
        PublishedEvent event = event();

        assertThat(consumer.handle(event)).isTrue();
        assertThat(consumer.handle(event)).isFalse(); // redelivery

        assertThat(ticketCount()).isEqualTo(1);
    }

    @Test
    void markAndEffectRollBackTogether() {
        PublishedEvent event = event();

        // El consumidor procesa, pero la tx termina en rollback (crash, error posterior...).
        // handle() es REQUIRED → se une a esta tx → el rollback se lleva la marca Y el ticket.
        tx.executeWithoutResult(status -> {
            consumer.handle(event);
            status.setRollbackOnly();
        });
        assertThat(ticketCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event", Integer.class)).isZero();

        // Como la marca no quedó, la próxima entrega SÍ se procesa: el evento no se perdió.
        assertThat(consumer.handle(event)).isTrue();
        assertThat(ticketCount()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateDeliveriesCreateOneTicket() throws Exception {
        PublishedEvent event = event();
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Boolean>> deliveries = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                deliveries.add(pool.submit(() -> {
                    startGate.await(10, TimeUnit.SECONDS);
                    return consumer.handle(event);
                }));
            }
            startGate.countDown();

            List<Boolean> processed = new ArrayList<>();
            for (Future<Boolean> d : deliveries) {
                processed.add(d.get(10, TimeUnit.SECONDS));
            }
            assertThat(processed).containsOnlyOnce(true);
        }
        assertThat(ticketCount()).isEqualTo(1);
    }

    @Test
    void endToEndWithLostAckOneTicketPerDiscrepancy() {
        ledgerRepository.saveAll(List.of(ledger("PR-1", "100.00"), ledger("PR-2", "50.00"), ledger("PR-3", "10.00")));
        facade.reconcile("PROC_BR", DAY, """
                processor_reference,amount,currency,settled_at
                PR-1,100.00,BRL,2026-11-12T10:00:00Z
                PR-2,55.00,BRL,2026-11-12T10:05:00Z
                PR-4,20.00,BRL,2026-11-12T10:10:00Z
                """.getBytes(StandardCharsets.UTF_8));
        // 3 discrepancias → 3 eventos en el outbox.

        // Broker que entrega al consumidor pero "pierde el ack" la primera vez de cada evento.
        Set<UUID> ackLostOnce = new HashSet<>();
        EventPublisher broker = event -> {
            consumer.handle(event); // entregado
            if (ackLostOnce.add(event.id())) {
                throw new IllegalStateException("ack lost");
            }
        };
        var publisher = new OutboxPublisher(jdbc, tx, broker, clock, 10, 5);

        publisher.publishBatch(); // 3 entregas, 3 acks perdidos → siguen pendientes
        publisher.publishBatch(); // 3 re-entregas → el consumidor las descarta

        // 6 entregas en total, 3 tickets: at-least-once + consumidor idempotente = efecto exactly-once.
        assertThat(ticketCount()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT event_type FROM discrepancy_ticket ORDER BY event_type", String.class))
                .containsExactly("AmountMismatch", "MissingInLedger", "MissingInProcessor");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class))
                .isZero();
    }

    private static PublishedEvent event() {
        return new PublishedEvent(UUID.randomUUID(), "AmountMismatch", "1", "{\"processorReference\": \"PR-2\"}");
    }

    private int ticketCount() {
        return jdbc.queryForObject("SELECT count(*) FROM discrepancy_ticket", Integer.class);
    }

    static LedgerEntryEntity ledger(String ref, String amount) {
        return LedgerEntryEntity.fromDomain(new LedgerEntry(
                ref, new Money(new BigDecimal(amount), Currency.getInstance("BRL")), "PROC_BR", DAY));
    }
}
