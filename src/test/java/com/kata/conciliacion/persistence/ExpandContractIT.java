package com.kata.conciliacion.persistence;

import com.kata.conciliacion.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Simula el expand/contract deploy por deploy. En la app real Flyway corre todas las migraciones
 * juntas al arrancar; acá, en un schema aparte, se frena en cada versión como si entre una y otra
 * pasaran días (con código viejo y nuevo escribiendo en la tabla).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExpandContractIT {

    static final String SCHEMA = "expand_contract";

    @Autowired PostgreSQLContainer<?> postgres;

    JdbcTemplate jdbc;
    PGSimpleDataSource dataSource;

    @BeforeEach
    void freshSchema() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dataSource.setCurrentSchema(SCHEMA);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void addMandatoryColumnWithoutDowntime() {
        // ---- Deploy 1, EXPAND: columna nullable. Código viejo (sin processor) sigue funcionando.
        migrateTo("4");
        long runId = insertRun("PROC_BR");
        insertResultWithoutProcessor(runId, "OLD-1"); // filas escritas por el código viejo
        insertResultWithoutProcessor(runId, "OLD-2");
        insertResultWithoutProcessor(runId, "OLD-3");
        insertResultWithProcessor(runId, "NEW-1");    // código nuevo ya la escribe

        // ---- Deploy 2, CONTRACT parte 1: CHECK NOT VALID. Las filas viejas con NULL se toleran...
        migrateTo("5");
        assertThat(countNullProcessor()).isEqualTo(3);
        // ...pero una escritura NUEVA con NULL ya es rechazada (si quedara código viejo, salta acá).
        assertThatThrownBy(() -> insertResultWithoutProcessor(runId, "OLD-4"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_result_processor_not_null");

        // ---- Backfill en lotes de 2 → 2 lotes para 4 filas (3 a completar).
        int updated = new ResultProcessorBackfill(jdbc).run(2);
        assertThat(updated).isEqualTo(3);
        assertThat(countNullProcessor()).isZero();
        // Idempotente: correrlo de nuevo no toca nada.
        assertThat(new ResultProcessorBackfill(jdbc).run(2)).isZero();

        // ---- Deploy 3, CONTRACT parte 2: VALIDATE + SET NOT NULL (sin scan) + DROP CHECK.
        migrateTo("6");
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = ? AND table_name = 'reconciliation_result' AND column_name = 'processor'
                """, String.class, SCHEMA)).isEqualTo("NO");
    }

    @Test
    void contractFailsIfBackfillDidNotFinish() {
        migrateTo("5");
        long runId = insertRun("PROC_BR");
        jdbc.execute("ALTER TABLE reconciliation_result DROP CONSTRAINT ck_result_processor_not_null");
        insertResultWithoutProcessor(runId, "OLD-1"); // fila vieja (antes del CHECK)
        jdbc.execute("""
                ALTER TABLE reconciliation_result
                ADD CONSTRAINT ck_result_processor_not_null CHECK (processor IS NOT NULL) NOT VALID
                """);

        // Sin backfill, VALIDATE encuentra el NULL y la migración falla entera (DDL transaccional
        // en Postgres: no queda a medias). Es la red de seguridad del orden de los deploys.
        assertThatThrownBy(() -> migrateTo("6"))
                .hasMessageContaining("ck_result_processor_not_null");
        assertThat(countNullProcessor()).isEqualTo(1);
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .target(version)
                .load()
                .migrate();
    }

    private long insertRun(String processor) {
        return jdbc.queryForObject("""
                INSERT INTO reconciliation_run (id, processor, settlement_date, status, started_at)
                VALUES (nextval('reconciliation_run_seq'), ?, DATE '2026-11-12', 'COMPLETED', now())
                RETURNING id
                """, Long.class, processor);
    }

    private void insertResultWithoutProcessor(long runId, String ref) {
        jdbc.update("""
                INSERT INTO reconciliation_result (id, run_id, settlement_date, processor_reference, result_type)
                VALUES (nextval('reconciliation_result_seq'), ?, DATE '2026-11-12', ?, 'MISSING_IN_LEDGER')
                """, runId, ref);
    }

    private void insertResultWithProcessor(long runId, String ref) {
        jdbc.update("""
                INSERT INTO reconciliation_result (id, run_id, settlement_date, processor, processor_reference, result_type)
                VALUES (nextval('reconciliation_result_seq'), ?, DATE '2026-11-12', 'PROC_BR', ?, 'MISSING_IN_LEDGER')
                """, runId, ref);
    }

    private int countNullProcessor() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reconciliation_result WHERE processor IS NULL", Integer.class);
    }
}
