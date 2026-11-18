-- T4c: reconciliation_result particionada por mes (RANGE sobre settlement_date).
--
-- Postgres NO convierte una tabla existente en particionada (no hay ALTER TABLE ... PARTITION BY).
-- Patrón: renombrar la vieja → crear la nueva particionada → mover datos → borrar la vieja.
-- Acá la tabla está vacía. Con miles de millones de filas NO harías INSERT ... SELECT en una
-- migración: o se adjunta la tabla vieja entera como partición "histórica" (ATTACH PARTITION con
-- un CHECK ya validado para evitar el scan), o se migra con dual write + backfill en lotes.

ALTER TABLE reconciliation_result RENAME TO reconciliation_result_old;
-- Los nombres de índices (incluida la PK) son únicos por schema, no por tabla: hay que liberarlos.
ALTER TABLE reconciliation_result_old RENAME CONSTRAINT reconciliation_result_pkey TO reconciliation_result_old_pkey;
ALTER INDEX ix_result_run RENAME TO ix_result_run_old;

CREATE TABLE reconciliation_result (
    id                  BIGINT       NOT NULL,
    run_id              BIGINT       NOT NULL REFERENCES reconciliation_run (id),
    settlement_date     DATE         NOT NULL,
    processor_reference VARCHAR(64)  NOT NULL,
    result_type         VARCHAR(24)  NOT NULL
        CONSTRAINT ck_result_type CHECK (result_type IN
            ('MATCHED', 'MISSING_IN_PROCESSOR', 'MISSING_IN_LEDGER', 'AMOUNT_MISMATCH', 'DUPLICATE')),
    ledger_amount       NUMERIC(19, 2),
    ledger_currency     VARCHAR(3),
    file_amount         NUMERIC(19, 2),
    file_currency       VARCHAR(3),
    duplicate_count     INT,
    -- Trampa: toda PK/UNIQUE de una tabla particionada DEBE incluir la clave de partición.
    -- Consecuencia: la base ya no garantiza que "id" sea único globalmente (solo por mes).
    -- Lo sigue siendo en la práctica porque sale de una secuencia.
    PRIMARY KEY (id, settlement_date)
) PARTITION BY RANGE (settlement_date);

-- Índice sobre la tabla padre → Postgres lo crea en cada partición (actual y futura).
CREATE INDEX ix_result_run ON reconciliation_result (run_id);

-- Particiones iniciales. Las siguientes las crea ResultPartitionMaintenance por adelantado.
-- Sin partición DEFAULT, a propósito (ver ResultPartitionMaintenance).
DO $$
DECLARE
    m DATE;
BEGIN
    FOR m IN SELECT generate_series(DATE '2026-01-01', DATE '2026-12-01', INTERVAL '1 month')::DATE LOOP
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF reconciliation_result FOR VALUES FROM (%L) TO (%L)',
            'reconciliation_result_' || to_char(m, 'YYYY_MM'), m, (m + INTERVAL '1 month')::DATE);
    END LOOP;
END $$;

INSERT INTO reconciliation_result SELECT * FROM reconciliation_result_old;
DROP TABLE reconciliation_result_old;
