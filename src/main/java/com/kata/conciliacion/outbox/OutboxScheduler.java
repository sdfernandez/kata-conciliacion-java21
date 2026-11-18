package com.kata.conciliacion.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polling del outbox. Separado de OutboxPublisher para poder apagarlo (en los tests, que llaman
 * al publisher a mano y no quieren un hilo de fondo robándose los eventos).
 *
 * <p>fixedDelay y no fixedRate: la próxima vuelta arranca X ms después de que TERMINÓ la anterior,
 * así un lote lento no se superpone con el siguiente. Entre instancias distintas del servicio la
 * coordinación la hace SKIP LOCKED, no el scheduler.
 */
@Component
@ConditionalOnProperty(prefix = "conciliacion.outbox", name = "scheduler-enabled", havingValue = "true",
        matchIfMissing = true)
class OutboxScheduler {

    private final OutboxPublisher publisher;

    OutboxScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${conciliacion.outbox.poll-interval-ms}")
    void tick() {
        publisher.publishBatch();
    }
}
