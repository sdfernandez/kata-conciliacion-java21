package com.kata.conciliacion.processor;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * Decora el cliente con Retry(CircuitBreaker(call)).
 *
 * <p>Por qué ese orden: el breaker ve CADA intento (3 timeouts seguidos = 3 fallas en su ventana)
 * y, cuando está abierto, lanza CallNotPermittedException, que el retry NO reintenta → falla
 * rápido. Al revés (CircuitBreaker(Retry(call))) el breaker solo vería "1 falla" después de
 * agotar todos los intentos y reaccionaría tarde. Es el mismo orden que usan las anotaciones de
 * Resilience4j por defecto.
 */
public class ResilientProcessorFileClient {

    private final ProcessorFileClient delegate;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public ResilientProcessorFileClient(ProcessorFileClient delegate, Settings settings) {
        this.delegate = delegate;
        this.retry = Retry.of("processor-api", RetryConfig.custom()
                .maxAttempts(settings.maxAttempts()) // incluye el primer intento
                // Backoff exponencial con jitter: base × 2^n, randomizado ±50%.
                // Sin jitter, N clientes que fallaron juntos reintentan juntos (thundering herd)
                // y vuelven a tumbar al procesador justo cuando se está recuperando.
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        settings.initialBackoff(), 2.0, 0.5, settings.maxBackoff()))
                // Whitelist: SOLO lo transitorio. Un 400, un bug nuestro (NPE) o el breaker
                // abierto no se reintentan.
                .retryExceptions(TransientProcessorException.class)
                .build());
        this.circuitBreaker = CircuitBreaker.of("processor-api", CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.windowSize())
                .minimumNumberOfCalls(settings.windowSize()) // no abrir por 1 falla de 1 llamada
                .failureRateThreshold(50)
                .waitDurationInOpenState(settings.openDuration())
                .permittedNumberOfCallsInHalfOpenState(2)
                // Solo lo transitorio cuenta como falla del procesador; un 404 es éxito
                // a los ojos del breaker.
                .recordExceptions(TransientProcessorException.class)
                .build());
    }

    public byte[] download(String processor, LocalDate settlementDate) {
        Supplier<byte[]> call = () -> delegate.download(processor, settlementDate);
        Supplier<byte[]> guarded = CircuitBreaker.decorateSupplier(circuitBreaker, call);
        return Retry.decorateSupplier(retry, guarded).get();
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    public Retry retry() {
        return retry;
    }

    public record Settings(int maxAttempts, Duration initialBackoff, Duration maxBackoff,
                           int windowSize, Duration openDuration) {

        /** Valores de producción: 4 intentos, 200ms→400→800 (±50%), breaker de 10 llamadas, 30 s abierto. */
        public static Settings defaults() {
            return new Settings(4, Duration.ofMillis(200), Duration.ofSeconds(5), 10, Duration.ofSeconds(30));
        }
    }
}
