package com.kata.conciliacion.outbox;

/**
 * El "broker". En producción: Kafka/SNS/RabbitMQ. Si este método retorna sin excepción, el broker
 * confirmó la recepción (ack). Si tira, no sabemos si llegó o no → hay que reintentar.
 */
public interface EventPublisher {

    void publish(PublishedEvent event);
}
