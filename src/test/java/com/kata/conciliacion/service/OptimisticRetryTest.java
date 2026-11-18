package com.kata.conciliacion.service;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimisticRetryTest {

    @Test
    void retriesOnConflictUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();

        String result = OptimisticRetry.execute(3, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new ObjectOptimisticLockingFailureException("LedgerEntry", 1L);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> OptimisticRetry.execute(3, () -> {
            calls.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException("LedgerEntry", 1L);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotRetryOtherErrors() {
        // Un error que no es conflicto (ej. constraint) no se arregla reintentando.
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> OptimisticRetry.execute(3, () -> {
            calls.incrementAndGet();
            throw new DataIntegrityViolationException("boom");
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(calls).hasValue(1);
    }
}
