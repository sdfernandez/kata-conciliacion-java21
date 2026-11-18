-- EXPAND (deploy 1): agregar la columna nullable. El código nuevo empieza a escribirla en el mismo deploy.
--
-- ADD COLUMN nullable y sin DEFAULT es solo un cambio de catálogo: no reescribe la tabla, es
-- instantáneo aunque tenga miles de millones de filas. (Desde PG 11 también lo es con un DEFAULT
-- constante; con un DEFAULT volátil como random() o clock_timestamp() reescribe todo.)
--
-- Trampa: aunque sea instantáneo, pide un lock ACCESS EXCLUSIVE. Si hay una query larga
-- corriendo, el ALTER espera... y todas las queries que llegan después esperan DETRÁS del ALTER.
-- Un ALTER "instantáneo" puede tirar la base abajo. lock_timeout: si no consigue el lock rápido,
-- falla (y se reintenta el deploy) en vez de encolar el tráfico. SET LOCAL = solo esta transacción.
SET LOCAL lock_timeout = '5s';

ALTER TABLE reconciliation_result ADD COLUMN processor VARCHAR(32);
