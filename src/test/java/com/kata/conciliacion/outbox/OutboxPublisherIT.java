package com.kata.conciliacion.outbox;

import com.kata.conciliacion.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
class OutboxPublisherIT {

    @Autowired OutboxEventRepository outboxRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired Clock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE reconciliation_result, reconciliation_run, ledger_entry, outbox_event, processed_event, discrepancy_ticket");
    }

    @Test
    void publishesPendingEventsAndMarksThemPublished() {
        List<UUID> ids = createEvents(5);
        var broker = new RecordingEventPublisher();

        int published = publisher(broker, 10, 3).publishBatch();

        assertThat(published).isEqualTo(5);
        assertThat(broker.received()).extracting(PublishedEvent::id).containsExactlyElementsOf(ids); // orden de creación
        assertThat(pendingCount()).isZero();
        // Segunda vuelta: no queda nada que publicar.
        assertThat(publisher(broker, 10, 3).publishBatch()).isZero();
    }

    @Test
    void twoConcurrentPublishersNeverPublishTheSameEvent() throws Exception {
        createEvents(50);
        // Broker lento (5 ms por evento) para que los dos publishers se superpongan de verdad.
        var brokerA = new RecordingEventPublisher(e -> sleep(5));
        var brokerB = new RecordingEventPublisher(e -> sleep(5));
        CountDownLatch startGate = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<?>> workers = new ArrayList<>();
            for (OutboxPublisher p : List.of(publisher(brokerA, 10, 3), publisher(brokerB, 10, 3))) {
                workers.add(pool.submit(() -> {
                    awaitGate(startGate);
                    while (p.publishBatch() > 0) {
                        // seguir mientras haya lotes que tomar
                    }
                }));
            }
            startGate.countDown();
            for (Future<?> w : workers) {
                w.get(30, TimeUnit.SECONDS);
            }
        }

        Set<UUID> fromA = idsOf(brokerA);
        Set<UUID> fromB = idsOf(brokerB);
        // Trabajaron los dos (paralelismo real)...
        assertThat(fromA).isNotEmpty();
        assertThat(fromB).isNotEmpty();
        // ...sin pisarse: ningún evento salió por los dos lados, y salieron todos.
        assertThat(fromA).doesNotContainAnyElementsOf(fromB);
        assertThat(fromA.size() + fromB.size()).isEqualTo(50);
        assertThat(pendingCount()).isZero();
    }

    @Test
    void lostAckMeansTheEventIsPublishedAgain() {
        UUID id = createEvents(1).getFirst();
        // El broker RECIBE el evento, pero el ack se pierde (timeout de red, pod que muere...).
        var flakyBroker = new RecordingEventPublisher(e -> {
            throw new IllegalStateException("ack lost");
        });
        publisher(flakyBroker, 10, 3).publishBatch();
        assertThat(pendingCount()).isEqualTo(1); // para nosotros no salió

        var healthyBroker = new RecordingEventPublisher();
        publisher(healthyBroker, 10, 3).publishBatch();

        // El mismo evento (mismo id) llegó DOS veces al broker: at-least-once.
        assertThat(flakyBroker.received()).extracting(PublishedEvent::id).containsExactly(id);
        assertThat(healthyBroker.received()).extracting(PublishedEvent::id).containsExactly(id);
        assertThat(pendingCount()).isZero();
    }

    @Test
    void poisonEventLeavesTheQueueAfterMaxAttempts() {
        createEvents(1);
        var brokenBroker = new RecordingEventPublisher(e -> {
            throw new IllegalStateException("schema rejected");
        });
        OutboxPublisher publisher = publisher(brokenBroker, 10, 3);

        for (int i = 0; i < 5; i++) {
            publisher.publishBatch();
        }

        // Se intentó 3 veces y después dejó de tomarlo: no bloquea la cola para siempre.
        assertThat(brokenBroker.received()).hasSize(3);
        // Dead letter: pendiente y sin más intentos. Esto es lo que dispara una alerta.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL AND attempts >= 3", Integer.class))
                .isEqualTo(1);
    }

    private OutboxPublisher publisher(EventPublisher broker, int batchSize, int maxAttempts) {
        return new OutboxPublisher(jdbc, tx, broker, clock, batchSize, maxAttempts);
    }

    private List<UUID> createEvents(int n) {
        Instant base = Instant.parse("2026-11-12T10:00:00Z");
        var events = IntStream.range(0, n)
                .mapToObj(i -> OutboxEventEntity.pending("ReconciliationRun", "1", "AmountMismatch",
                        "{\"n\": " + i + "}", base.plusSeconds(i)))
                .toList();
        outboxRepository.saveAll(events);
        return events.stream().map(OutboxEventEntity::getId).toList();
    }

    private int pendingCount() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class);
    }

    private static Set<UUID> idsOf(RecordingEventPublisher broker) {
        List<UUID> ids = broker.received().stream().map(PublishedEvent::id).toList();
        Set<UUID> unique = new HashSet<>(ids);
        assertThat(unique).hasSameSizeAs(ids); // tampoco duplicados dentro de un mismo publisher
        return unique;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitGate(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
