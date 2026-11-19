package com.kata.conciliacion.processor;

/**
 * Jerarquía cerrada: todo error de la API del procesador es O transitorio O permanente.
 * El retry y el circuit breaker deciden mirando el TIPO, no parseando mensajes.
 */
public sealed abstract class ProcessorApiException extends RuntimeException
        permits TransientProcessorException, PermanentProcessorException {

    protected ProcessorApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
