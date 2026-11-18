package com.kata.conciliacion.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Backfill de reconciliation_result.processor (fase "migrate" del expand/contract).
 *
 * <p>Un solo UPDATE ... FROM sobre toda la tabla sería una transacción gigante: locks sobre
 * millones de filas, WAL masivo de golpe, réplicas atrasadas, VACUUM bloqueado y, si falla a
 * la mitad, rollback de todo. En lotes, cada uno es su propia transacción corta, se puede
 * pausar, reanudar (es idempotente: solo toca filas con NULL) y frenar si la base sufre.
 *
 * <p>Keyset pagination (id > último procesado) y no "WHERE processor IS NULL LIMIT n": con la
 * segunda, cada lote vuelve a recorrer desde el principio todas las filas ya completadas para
 * encontrar las que faltan → cada lote es más lento que el anterior (O(n²) en total).
 *
 * <p>No es un @Component: en producción sería un job one-off (o un comando de admin) con
 * throttling y métrica de progreso; acá lo instancia el test contra un schema propio.
 */
public class ResultProcessorBackfill {

    private static final Logger log = LoggerFactory.getLogger(ResultProcessorBackfill.class);

    private final JdbcTemplate jdbc;

    public ResultProcessorBackfill(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Completa processor en lotes de {@code batchSize}. Devuelve cuántas filas actualizó. */
    public int run(int batchSize) {
        long lastId = 0;
        int total = 0;
        while (true) {
            // Límite superior del próximo lote: el id número batchSize después de lastId.
            Long upperId = jdbc.queryForObject("""
                    SELECT max(id) FROM (
                        SELECT id FROM reconciliation_result WHERE id > ? ORDER BY id LIMIT ?
                    ) batch
                    """, Long.class, lastId, batchSize);
            if (upperId == null) {
                break; // no quedan filas
            }

            // Sin @Transactional: autocommit → cada lote es una transacción corta e independiente.
            int updated = jdbc.update("""
                    UPDATE reconciliation_result r
                    SET processor = run.processor
                    FROM reconciliation_run run
                    WHERE run.id = r.run_id
                      AND r.id > ? AND r.id <= ?
                      AND r.processor IS NULL
                    """, lastId, upperId);

            total += updated;
            log.info("Backfill processor: ids ({}, {}] → {} rows", lastId, upperId, updated);
            lastId = upperId;
            // En producción: Thread.sleep(...) entre lotes y cortar si el lag de réplicas sube.
        }
        return total;
    }
}
