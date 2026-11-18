package com.kata.conciliacion.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Sin Kafka en la kata: "publicar" = loguear. Lo que importa es el patrón, no el broker. */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(PublishedEvent event) {
        log.info("Published {} {} for run {}: {}", event.eventType(), event.id(), event.aggregateId(),
                event.payload());
    }
}
