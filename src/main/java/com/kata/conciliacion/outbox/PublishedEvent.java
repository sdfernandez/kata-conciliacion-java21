package com.kata.conciliacion.outbox;

import java.util.UUID;

/** Lo que viaja al broker. El id es el que usa el consumidor para deduplicar. */
public record PublishedEvent(UUID id, String eventType, String aggregateId, String payload) {
}
