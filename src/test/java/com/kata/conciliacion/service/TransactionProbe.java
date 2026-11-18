package com.kata.conciliacion.service;

import java.io.IOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bean de laboratorio para las trampas de @Transactional. Vive en src/test pero con @Component:
 * el component scan lo levanta en todos los contextos de test sin cambiar la clave del cache.
 *
 * <p>Cada método "deja una marca": inserta una run con el processor que recibe, así el test
 * puede ver después si quedó commiteada o no.
 */
@Component
public class TransactionProbe {

    private final JdbcTemplate jdbc;

    public TransactionProbe(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- self-invocation -------------------------------------------------------------------

    @Transactional
    public boolean transactionalMethod() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    /** NO es @Transactional y llama al de arriba con this.: no pasa por el proxy. */
    public boolean callsTransactionalMethodOnThis() {
        return this.transactionalMethod();
    }

    // ---- rollback rules --------------------------------------------------------------------

    @Transactional
    public void insertThenThrowUnchecked(String marker) {
        insertMarker(marker);
        throw new IllegalStateException("boom");
    }

    @Transactional
    public void insertThenThrowChecked(String marker) throws IOException {
        insertMarker(marker);
        throw new IOException("boom");
    }

    @Transactional(rollbackFor = Exception.class)
    public void insertThenThrowCheckedWithRollbackFor(String marker) throws IOException {
        insertMarker(marker);
        throw new IOException("boom");
    }

    private void insertMarker(String marker) {
        // JdbcTemplate participa de la misma transacción que abrió JpaTransactionManager:
        // Spring expone la misma conexión JDBC atada al hilo.
        jdbc.update("""
                INSERT INTO reconciliation_run (id, processor, settlement_date, status, started_at)
                VALUES (nextval('reconciliation_run_seq'), ?, DATE '2026-11-12', 'RUNNING', now())
                """, marker);
    }
}
