-- T5: clave de idempotencia de una run = processor + settlement_date + sha256(archivo).
SET LOCAL lock_timeout = '5s';

-- Nullable por compatibilidad con filas viejas. En producción se volvería NOT NULL con
-- expand/contract (V4-V6). El código nuevo siempre la completa.
ALTER TABLE reconciliation_run ADD COLUMN file_hash VARCHAR(64);

-- Índice único PARCIAL: las runs FAILED no ocupan la clave. Si una run falla, el reintento del
-- mismo archivo tiene que poder correr; con un UNIQUE común quedaría bloqueado para siempre.
-- (Tabla chica: CREATE INDEX normal. Sobre una tabla grande sería CONCURRENTLY, en su propia
-- migración y fuera de transacción.)
CREATE UNIQUE INDEX uq_run_idempotency_key
    ON reconciliation_run (processor, settlement_date, file_hash)
    WHERE status <> 'FAILED';
