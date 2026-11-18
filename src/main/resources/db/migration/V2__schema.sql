-- T4: esquema de conciliación.
-- IDs con SEQUENCE (no IDENTITY): Hibernate puede pedir un bloque de IDs por adelantado
-- y mandar los INSERT en batch. Con IDENTITY necesita el ID de cada fila apenas la inserta
-- y desactiva el batching. INCREMENT BY 50 = allocationSize de las entidades (pooled optimizer).

CREATE SEQUENCE ledger_entry_seq INCREMENT BY 50;
CREATE SEQUENCE reconciliation_run_seq INCREMENT BY 50;
CREATE SEQUENCE reconciliation_result_seq INCREMENT BY 50;

CREATE TABLE ledger_entry (
    id                  BIGINT       PRIMARY KEY,
    processor           VARCHAR(32)  NOT NULL,
    processor_reference VARCHAR(64)  NOT NULL,
    settlement_date     DATE         NOT NULL,
    amount              NUMERIC(19, 2) NOT NULL,
    currency            VARCHAR(3)   NOT NULL,
    -- Una referencia es única dentro de un procesador. El motor asume esto (tira excepción si no).
    CONSTRAINT uq_ledger_entry_processor_ref UNIQUE (processor, processor_reference)
);

-- La query del servicio: WHERE processor = ? AND settlement_date = ?
-- Igualdad en las dos columnas, así que el orden importa poco; processor primero porque
-- también sirve para "todo lo de un procesador".
CREATE INDEX ix_ledger_entry_processor_date ON ledger_entry (processor, settlement_date);

CREATE TABLE reconciliation_run (
    id              BIGINT       PRIMARY KEY,
    processor       VARCHAR(32)  NOT NULL,
    settlement_date DATE         NOT NULL,
    status          VARCHAR(16)  NOT NULL
        CONSTRAINT ck_run_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    started_at      TIMESTAMPTZ  NOT NULL,
    finished_at     TIMESTAMPTZ
);

CREATE INDEX ix_run_processor_date ON reconciliation_run (processor, settlement_date);

-- Una sola tabla para los 5 tipos: las columnas de ledger o de archivo quedan NULL según el caso.
-- settlement_date se denormaliza desde la run: va a ser la clave de partición (T4c).
CREATE TABLE reconciliation_result (
    id                  BIGINT       PRIMARY KEY,
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
    duplicate_count     INT
);

-- Postgres NO crea índices sobre las FK automáticamente (MySQL sí). Sin esto, "resultados de
-- una run" es un seq scan, y borrar una run escanea toda la tabla de resultados.
CREATE INDEX ix_result_run ON reconciliation_result (run_id);
