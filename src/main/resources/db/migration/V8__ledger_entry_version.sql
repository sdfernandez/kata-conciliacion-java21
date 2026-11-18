-- T5: locking optimista sobre ledger_entry + marca de "conciliada por la run X".
SET LOCAL lock_timeout = '5s';

-- DEFAULT constante → desde PG 11 es instantáneo (no reescribe la tabla): el valor se guarda en
-- el catálogo y se "materializa" al leer las filas viejas.
ALTER TABLE ledger_entry ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE ledger_entry ADD COLUMN reconciled_run_id BIGINT REFERENCES reconciliation_run (id);
-- Índice sobre la FK: Postgres no lo crea solo, y sin él borrar una run escanea todo el ledger.
CREATE INDEX ix_ledger_entry_reconciled_run ON ledger_entry (reconciled_run_id);
