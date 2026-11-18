-- CONTRACT, parte 1 (deploy 2, cuando el código nuevo ya escribe processor en todas las filas nuevas).
--
-- CHECK ... NOT VALID: se aplica a partir de ahora a INSERT/UPDATE, pero NO revisa las filas
-- existentes → no hay scan, el lock fuerte dura milisegundos. Las filas viejas con NULL se toleran
-- hasta que termine el backfill.
--
-- Va en una migración SEPARADA de la VALIDATE: Flyway corre cada archivo en una transacción, y si
-- ADD (lock ACCESS EXCLUSIVE) y VALIDATE (scan largo) estuvieran juntos, el lock fuerte quedaría
-- tomado durante todo el scan. Eso es exactamente lo que queremos evitar.
SET LOCAL lock_timeout = '5s';

ALTER TABLE reconciliation_result
    ADD CONSTRAINT ck_result_processor_not_null CHECK (processor IS NOT NULL) NOT VALID;
