package com.kata.conciliacion.service;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Reintento acotado ante conflictos de locking optimista.
 *
 * <p>Regla de oro: se reintenta la TRANSACCIÓN ENTERA, desde afuera. Reintentar adentro no sirve:
 * la tx ya está marcada rollback-only y el contexto de persistencia tiene las entidades viejas
 * (las mismas que causaron el conflicto). Cada intento tiene que abrir una tx nueva y releer.
 *
 * <p>Sin backoff acá para no adelantar T8 (ahí: backoff exponencial + jitter).
 */
final class OptimisticRetry {

    private static final Logger log = LoggerFactory.getLogger(OptimisticRetry.class);

    private OptimisticRetry() {
    }

    static <T> T execute(int maxAttempts, Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    throw e; // conflicto persistente: que lo vea alguien, no reintentar para siempre
                }
                log.warn("Optimistic lock conflict (attempt {}/{}), retrying: {}", attempt, maxAttempts,
                        e.getMessage());
            }
        }
    }
}
