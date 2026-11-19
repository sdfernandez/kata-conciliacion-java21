package com.kata.conciliacion.processor;

/**
 * Reintentar no cambia el resultado: 400/401/403/404. NO cuenta para el breaker:
 * un request mal armado es culpa nuestra, no señal de que el procesador esté caído.
 */
public final class PermanentProcessorException extends ProcessorApiException {

    private final int status;

    public PermanentProcessorException(String message, int status) {
        super(message, null);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
