-- T6: patrón outbox.
-- El evento se inserta en la MISMA transacción que el resultado que lo origina. Un publisher
-- aparte lo lleva al broker después (at-least-once).

CREATE TABLE outbox_event (
    -- UUID generado por la app: es el ID del evento "hacia afuera"; el consumidor deduplica con él.
    -- (UUIDv4 es aleatorio y fragmenta el índice B-tree; en producción conviene UUIDv7, ordenado
    -- por tiempo. Java 21 no lo trae: se usa una librería o se genera a mano.)
    id             UUID         PRIMARY KEY,
    aggregate_type VARCHAR(32)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(48)  NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0
);

-- Índice PARCIAL: solo las filas pendientes. La tabla crece para siempre (hasta que se limpie),
-- pero el índice que usa el publisher queda chico: tiene solo lo que falta publicar.
CREATE INDEX ix_outbox_pending ON outbox_event (created_at) WHERE published_at IS NULL;

-- ---- Lado consumidor ----------------------------------------------------------------------
-- En la vida real esto vive en la base del servicio que consume (otro equipo, otro deploy).
-- Está acá solo para la kata.

-- Registro de eventos ya procesados: la PK es la que hace idempotente al consumidor.
CREATE TABLE processed_event (
    event_id     UUID         PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL
);

-- El "efecto" del consumidor: un ticket para que alguien investigue la discrepancia.
CREATE TABLE discrepancy_ticket (
    id         BIGSERIAL    PRIMARY KEY,
    event_id   UUID         NOT NULL,
    event_type VARCHAR(48)  NOT NULL,
    payload    JSONB        NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);
