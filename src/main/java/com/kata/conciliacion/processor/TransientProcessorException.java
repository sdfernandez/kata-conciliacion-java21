package com.kata.conciliacion.processor;

/** Puede salir bien si reintentamos: 5xx, 429, timeout, conexión caída. Cuenta para el breaker. */
public final class TransientProcessorException extends ProcessorApiException {

    public TransientProcessorException(String message, Throwable cause) {
        super(message, cause);
    }
}
