package com.kata.conciliacion.outbox;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** Broker falso para tests: guarda lo que recibe y permite inyectar demoras o fallas. */
class RecordingEventPublisher implements EventPublisher {

    final ConcurrentLinkedQueue<PublishedEvent> received = new ConcurrentLinkedQueue<>();
    private final Consumer<PublishedEvent> afterReceive;

    RecordingEventPublisher() {
        this(e -> { });
    }

    /** afterReceive corre DESPUÉS de que el broker "recibió" el evento (puede tirar: ack perdido). */
    RecordingEventPublisher(Consumer<PublishedEvent> afterReceive) {
        this.afterReceive = afterReceive;
    }

    @Override
    public void publish(PublishedEvent event) {
        received.add(event);
        afterReceive.accept(event);
    }

    List<PublishedEvent> received() {
        return List.copyOf(received);
    }
}
