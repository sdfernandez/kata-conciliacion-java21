package com.kata.conciliacion.service;

import com.kata.conciliacion.TestcontainersConfiguration;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransactionalPitfallsIT {

    @Autowired TransactionProbe probe;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE reconciliation_result, reconciliation_run, ledger_entry, outbox_event, processed_event, discrepancy_ticket");
    }

    // ---- self-invocation -------------------------------------------------------------------

    @Test
    void injectedBeanIsAProxy() {
        // Lo que Spring inyecta no es TransactionProbe: es una subclase CGLIB que envuelve cada
        // llamada con "abrir tx → delegar al objeto real → commit/rollback".
        assertThat(AopUtils.isCglibProxy(probe)).isTrue();
    }

    @Test
    void callThroughProxyOpensTransaction() {
        assertThat(probe.transactionalMethod()).isTrue();
    }

    @Test
    void selfInvocationDoesNotOpenTransaction() {
        // El proxy intercepta callsTransactionalMethodOnThis() (sin @Transactional → no hace nada)
        // y delega al objeto real. Adentro, this.transactionalMethod() es una llamada Java común
        // sobre el objeto real: el proxy nunca se entera y la anotación se ignora EN SILENCIO.
        assertThat(probe.callsTransactionalMethodOnThis()).isFalse();
    }

    // ---- rollback rules --------------------------------------------------------------------

    @Test
    void uncheckedExceptionRollsBack() {
        assertThatThrownBy(() -> probe.insertThenThrowUnchecked("UNCHECKED"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(markerCount("UNCHECKED")).isZero();
    }

    @Test
    void checkedExceptionCommitsByDefault() {
        // La sorpresa: Spring (heredado de EJB) solo hace rollback ante RuntimeException y Error.
        // Una checked exception se considera "resultado de negocio esperado" → COMMIT.
        assertThatThrownBy(() -> probe.insertThenThrowChecked("CHECKED"))
                .isInstanceOf(IOException.class);

        assertThat(markerCount("CHECKED")).isEqualTo(1);
    }

    @Test
    void checkedExceptionWithRollbackForRollsBack() {
        assertThatThrownBy(() -> probe.insertThenThrowCheckedWithRollbackFor("ROLLBACK_FOR"))
                .isInstanceOf(IOException.class);

        assertThat(markerCount("ROLLBACK_FOR")).isZero();
    }

    private int markerCount(String marker) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM reconciliation_run WHERE processor = ?", Integer.class, marker);
    }
}
