-- CONTRACT, parte 2 (deploy 3, cuando el backfill terminó: no quedan NULL).
--
-- 1) VALIDATE: escanea la tabla, pero con lock SHARE UPDATE EXCLUSIVE, que NO bloquea lecturas
--    ni escrituras. Si quedara algún NULL, falla y no cambia nada.
-- 2) SET NOT NULL: normalmente escanea toda la tabla con lock ACCESS EXCLUSIVE (todo bloqueado).
--    Desde PG 12, si existe un CHECK (col IS NOT NULL) ya validado, Postgres lo usa como prueba
--    y se saltea el scan → lock de milisegundos.
-- 3) El CHECK ya no hace falta: el NOT NULL de la columna lo reemplaza.
SET LOCAL lock_timeout = '5s';

ALTER TABLE reconciliation_result VALIDATE CONSTRAINT ck_result_processor_not_null;
ALTER TABLE reconciliation_result ALTER COLUMN processor SET NOT NULL;
ALTER TABLE reconciliation_result DROP CONSTRAINT ck_result_processor_not_null;
