package com.kata.conciliacion.persistence;

import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Mantenimiento de particiones mensuales de reconciliation_result:
 * <ul>
 *   <li>Crea las de los próximos meses por adelantado (al arrancar y una vez por día).
 *   <li>Retención: DETACH + DROP de las más viejas que N meses, en vez de DELETE masivo.
 * </ul>
 *
 * <p>Sin partición DEFAULT, a propósito: (1) DETACH ... CONCURRENTLY no se permite si existe
 * una DEFAULT; (2) crear una partición nueva obliga a escanear la DEFAULT (con lock) para
 * verificar que no tenga filas de ese rango; (3) la DEFAULT esconde el problema. Sin ella, un
 * mes sin partición hace fallar el INSERT → run FAILED → alerta. Fallar fuerte es mejor que
 * acumular basura en silencio.
 *
 * <p>Sin @Transactional a propósito: DETACH ... CONCURRENTLY no puede correr dentro de un bloque
 * de transacción. JdbcTemplate sin tx activa usa autocommit: cada sentencia es su propia tx.
 */
@Component
public class ResultPartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(ResultPartitionMaintenance.class);
    private static final String PARENT = "reconciliation_result";
    private static final Pattern PARTITION_NAME = Pattern.compile(PARENT + "_(\\d{4})_(\\d{2})");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int retentionMonths;
    private final int monthsAhead;

    public ResultPartitionMaintenance(JdbcTemplate jdbc, Clock clock,
                                      @Value("${conciliacion.partitions.retention-months}") int retentionMonths,
                                      @Value("${conciliacion.partitions.months-ahead}") int monthsAhead) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.retentionMonths = retentionMonths;
        this.monthsAhead = monthsAhead;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "${conciliacion.partitions.cron}")
    public void maintain() {
        YearMonth current = YearMonth.now(clock);
        createPartitions(current, monthsAhead + 1);
        dropPartitionsOlderThan(current.minusMonths(retentionMonths));
    }

    /** Crea {@code count} particiones mensuales a partir de {@code from}. Idempotente. */
    public void createPartitions(YearMonth from, int count) {
        for (int i = 0; i < count; i++) {
            YearMonth month = from.plusMonths(i);
            jdbc.execute("CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')"
                    .formatted(partitionName(month), PARENT, month.atDay(1), month.plusMonths(1).atDay(1)));
        }
    }

    /** Desengancha y borra las particiones de meses anteriores a {@code cutoff}. Devuelve cuáles. */
    public List<String> dropPartitionsOlderThan(YearMonth cutoff) {
        List<String> dropped = new ArrayList<>();
        for (String name : existingPartitions()) {
            Matcher m = PARTITION_NAME.matcher(name);
            if (!m.matches()) {
                continue; // no es nuestra convención: no la tocamos
            }
            YearMonth month = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            if (month.isBefore(cutoff)) {
                // 1) DETACH CONCURRENTLY (PG 14+): la saca de la tabla padre sin bloquear las
                //    lecturas/escrituras sobre el resto. Desde acá es una tabla suelta.
                // 2) En un sistema financiero real, entre 1 y 2 se ARCHIVA (export a cold storage):
                //    la retención legal de datos contables suele ser de años.
                // 3) DROP: borra un archivo. Instantáneo, sin WAL por fila, sin bloat, sin VACUUM.
                jdbc.execute("ALTER TABLE %s DETACH PARTITION %s CONCURRENTLY".formatted(PARENT, name));
                jdbc.execute("DROP TABLE %s".formatted(name));
                log.info("Retention: detached and dropped partition {}", name);
                dropped.add(name);
            }
        }
        return dropped;
    }

    public List<String> existingPartitions() {
        // Los nombres salen del catálogo y se filtran con un regex estricto antes de concatenarlos
        // en SQL: DDL no admite parámetros (?), así que no hay otra forma; validar es obligatorio.
        return jdbc.queryForList("""
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = ?
                ORDER BY c.relname
                """, String.class, PARENT);
    }

    private static String partitionName(YearMonth month) {
        return "%s_%04d_%02d".formatted(PARENT, month.getYear(), month.getMonthValue());
    }
}
