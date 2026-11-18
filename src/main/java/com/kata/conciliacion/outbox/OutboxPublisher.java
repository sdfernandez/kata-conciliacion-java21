package com.kata.conciliacion.outbox;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lleva los eventos pendientes del outbox al broker. Garantía: AT-LEAST-ONCE.
 *
 * <p>Orden de operaciones por evento: publicar → marcar publicado → commit. Si el proceso muere
 * después de publicar y antes del commit, el evento sigue pendiente y se vuelve a publicar:
 * duplicado posible, pérdida imposible. El orden inverso (marcar → publicar) daría at-most-once:
 * si muere en el medio, el evento queda marcado y nunca sale. En finanzas, perder es peor que
 * duplicar, porque el duplicado se resuelve con un consumidor idempotente.
 *
 * <p>JDBC y no JPA: necesitamos SQL exacto (FOR UPDATE SKIP LOCKED, UPDATE puntual) y no
 * queremos entidades managed ni dirty checking en un loop de polling.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private static final RowMapper<PublishedEvent> ROW_MAPPER = (rs, i) -> new PublishedEvent(
            rs.getObject("id", UUID.class),
            rs.getString("event_type"),
            rs.getString("aggregate_id"),
            rs.getString("payload"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxPublisher(JdbcTemplate jdbc, TransactionTemplate tx, EventPublisher publisher, Clock clock,
                           @Value("${conciliacion.outbox.batch-size}") int batchSize,
                           @Value("${conciliacion.outbox.max-attempts}") int maxAttempts) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /** Toma un lote, lo publica y lo marca. Devuelve cuántos se publicaron OK. */
    public int publishBatch() {
        // Una tx por lote: los locks de FOR UPDATE viven hasta el commit de este bloque.
        Integer published = tx.execute(status -> {
            // FOR UPDATE: lockea las filas tomadas, nadie más las procesa mientras tanto.
            // SKIP LOCKED: si otra instancia ya lockeó una fila, la SALTEA en vez de esperarla.
            //   Sin SKIP LOCKED, la segunda instancia quedaría bloqueada esperando a la primera
            //   (cero paralelismo); con NOWAIT, fallaría. Con SKIP LOCKED cada una toma otro lote.
            // attempts < max: un evento "veneno" que siempre falla no se reintenta para siempre.
            List<PublishedEvent> batch = jdbc.query("""
                    SELECT id, event_type, aggregate_id, payload::text AS payload
                    FROM outbox_event
                    WHERE published_at IS NULL AND attempts < ?
                    ORDER BY created_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                    """, ROW_MAPPER, maxAttempts, batchSize);

            int ok = 0;
            for (PublishedEvent event : batch) {
                try {
                    publisher.publish(event);
                    jdbc.update("UPDATE outbox_event SET published_at = ?, attempts = attempts + 1 WHERE id = ?",
                            Timestamp.from(clock.instant()), event.id());
                    ok++;
                } catch (RuntimeException e) {
                    // Un evento que falla no frena al resto del lote. Queda pendiente con un
                    // intento más; al llegar a maxAttempts sale de la cola (dead letter → alerta).
                    jdbc.update("UPDATE outbox_event SET attempts = attempts + 1 WHERE id = ?", event.id());
                    log.warn("Failed to publish event {} ({}): {}", event.id(), event.eventType(), e.getMessage());
                }
            }
            return ok;
        });
        return published == null ? 0 : published;
    }
}
